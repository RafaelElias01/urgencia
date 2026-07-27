package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.*;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.repository.ProcessoRepository;
import br.gov.saude.sgpur.repository.SolicitacaoOnlineRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.time.LocalDateTime;
import java.time.Year;
import java.util.List;
import java.util.Optional;

@Service
public class ProcessoService {

    /** A partir deste ano a numeracao passa a ser automatica (2026 e manual). */
    private static final int ANO_NUMERACAO_AUTOMATICA = 2027;

    /** Cada processo e enviado a exatamente 3 medicos avaliadores. */
    public static final int AVALIADORES_POR_PROCESSO = 3;

    /** Quantidade de pareceres favoraveis necessaria para deferir (maioria simples). */
    public static final int FAVORAVEIS_PARA_DEFERIR = 2;

    /** Quantidade de pareceres desfavoraveis necessaria para indeferir (maioria simples). */
    public static final int DESFAVORAVEIS_PARA_INDEFERIR = 2;

    private static final Logger log = LoggerFactory.getLogger(ProcessoService.class);

    private final ProcessoRepository processoRepository;
    private final MembroUrgenciaRenalRepository membroRepository;
    private final ProcessoValidator validator;
    private final ParecerRepository parecerRepository;
    private final SolicitacaoOnlineRepository solicitacaoOnlineRepository;
    private final EmailTemplateService emailTemplateService;
    private final EmailSenderService emailSenderService;
    private final AnexoStorageService anexoStorageService;
    private final AuditoriaService auditoriaService;

    public ProcessoService(ProcessoRepository processoRepository,
                           MembroUrgenciaRenalRepository membroRepository,
                           ProcessoValidator validator,
                           ParecerRepository parecerRepository,
                           SolicitacaoOnlineRepository solicitacaoOnlineRepository,
                           EmailTemplateService emailTemplateService,
                           EmailSenderService emailSenderService,
                           AnexoStorageService anexoStorageService,
                           AuditoriaService auditoriaService) {
        this.processoRepository = processoRepository;
        this.membroRepository = membroRepository;
        this.validator = validator;
        this.parecerRepository = parecerRepository;
        this.solicitacaoOnlineRepository = solicitacaoOnlineRepository;
        this.emailTemplateService = emailTemplateService;
        this.emailSenderService = emailSenderService;
        this.anexoStorageService = anexoStorageService;
        this.auditoriaService = auditoriaService;
    }

    public List<Processo> listarTodos() {
        return processoRepository.findAllByOrderByAnoDescSequencialDesc();
    }

    public org.springframework.data.domain.Page<Processo> buscar(
            String q, StatusProcesso status, org.springframework.data.domain.Pageable pageable) {
        String termo = (q == null || q.isBlank()) ? null : q.trim();
        return processoRepository.buscar(termo, status, pageable);
    }

    public Processo buscar(Long id) {
        return processoRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Processo nao encontrado: " + id));
    }

    /** Numeracao automatica a partir de 2027; nos anos anteriores e manual. */
    public boolean isNumeracaoAutomatica(int ano) {
        return ano >= ANO_NUMERACAO_AUTOMATICA;
    }

    public boolean numeroJaExiste(String numero) {
        return numero != null && processoRepository.findByNumero(numero).isPresent();
    }

    /** Proximo numero NN/AAAA para um ano (quando automatico). */
    public String proximoNumero(int ano) {
        Integer max = processoRepository.findMaxSequencialByAno(ano);
        int seq = (max == null ? 0 : max) + 1;
        return String.format("%02d/%d", seq, ano);
    }

    /**
     * Salva um novo processo. Gera o numero automaticamente quando o ano
     * estiver no regime automatico; caso contrario usa o numero informado.
     * Cria um parecer pendente para cada um dos 3 medicos escolhidos.
     */
    @Transactional
    public Processo cadastrar(Processo processo, List<Long> medicoIds) {
        if (medicoIds == null || medicoIds.size() != AVALIADORES_POR_PROCESSO) {
            throw new IllegalArgumentException(
                "Selecione exatamente " + AVALIADORES_POR_PROCESSO + " medicos avaliadores.");
        }
        // Defesa contra mass assignment: o form faz bind da entidade Processo
        // inteira (@ModelAttribute), entao um request malicioso poderia incluir
        // status=DEFERIDO/INDEFERIDO/CANCELADO e outros campos que so fazem
        // sentido apos a decisao. Um processo novo SEMPRE nasce SOLICITADO, sem
        // decisao registrada. Tambem forca id=null: sem isso, um request com um
        // id de processo EXISTENTE faria o save() abaixo cair em merge() em vez
        // de persist(), sobrescrevendo o processo alvo e apagando seus pareceres
        // e anexos reais (cascade=ALL + orphanRemoval=true), ate mesmo se ja
        // ENCERRADO - sem passar pelo ProcessoValidator.edicaoBloqueada.
        processo.setId(null);
        processo.setStatus(StatusProcesso.SOLICITADO);
        processo.setDataDecisao(null);
        processo.setMotivoIndeferimento(null);
        processo.setEmailEnviadoSolicitante(false);
        int ano = processo.getDataSituacaoEspecial() != null
            ? processo.getDataSituacaoEspecial().getYear()
            : Year.now().getValue();
        processo.setAno(ano);

        if (isNumeracaoAutomatica(ano)) {
            processo.setNumero(proximoNumero(ano));
        }
        processo.setSequencial(extrairSequencial(processo.getNumero(), ano));

        // cria um parecer pendente para cada medico escolhido
        for (Long medicoId : medicoIds) {
            MembroUrgenciaRenal medico = membroRepository.findById(medicoId)
                .orElseThrow(() -> new IllegalArgumentException("Medico nao encontrado: " + medicoId));
            processo.addParecer(new Parecer(medico));
        }
        // proximoNumero() calcula MAX(sequencial)+1 sem lock: dois cadastros
        // simultaneos no mesmo ano podem calcular o mesmo numero. A constraint
        // UNIQUE em Processo.numero ja impede a duplicata no banco, e o
        // GlobalExceptionHandler ja traduz a DataIntegrityViolationException
        // resultante numa mensagem amigavel - nao precisa de tratamento aqui.
        return processoRepository.save(processo);
    }

    @Transactional
    public Processo salvar(Processo processo) {
        return processoRepository.save(processo);
    }

    /**
     * Marca o processo como ENVIADO aos avaliadores (etapa 5 do fluxo).
     * So altera o status quando ainda esta em uma fase anterior a decisao
     * (SOLICITADO / ENVIADO / EM_ANALISE / SOLICITA_INFORMACAO); nunca rebaixa
     * um processo ja decidido.
     */
    @Transactional
    public Processo registrarEnvio(Long id) {
        Processo p = buscar(id);
        // Defesa em profundidade: mesma checagem ja feita na camada web
        // (comprovante de envio + documento clinico PDF), imposta aqui para
        // que o metodo nunca marque ENVIADO sem essas garantias minimas,
        // mesmo se chamado de outro lugar no futuro (outro controller, job,
        // teste) sem passar pela validacao do controller HTTP.
        validator.validarRegistroEnvio(p)
            .ifPresent(msg -> { throw new IllegalStateException(msg); });
        if (p.getStatus().isEmAndamento()) {
            p.setStatus(StatusProcesso.ENVIADO);
        }
        return processoRepository.save(p);
    }

    /**
     * Recalcula o status "em andamento" do processo a partir dos pareceres
     * recebidos, SEM tomar a decisao final (que continua manual via decidir()):
     * - se algum medico pediu informacao e o processo ainda nao foi decidido,
     *   o status vai para SOLICITA_INFORMACAO;
     * - caso contrario permanece ENVIADO (ja foi enviado aos medicos).
     * Processo ja finalizado (DEFERIDO/INDEFERIDO/CANCELADO) nao pode ser
     * chamado aqui — o caller deve barrar antes (guarda de edicaoBloqueada);
     * chegar com um processo finalizado e erro de programacao, nao um caso
     * valido a silenciar.
     */
    @Transactional
    public Processo atualizarStatusPorPareceres(Long id) {
        Processo p = buscar(id);
        if (p.getStatus().isFinalizado()) {
            throw new IllegalStateException(ProcessoValidator.MSG_ENCERRADO);
        }
        boolean pediuInfo = p.getPareceres().stream()
            .anyMatch(par -> par.getResultado() == ResultadoParecer.SOLICITA_INFORMACAO);
        p.setStatus(pediuInfo ? StatusProcesso.SOLICITA_INFORMACAO : StatusProcesso.ENVIADO);
        return processoRepository.save(p);
    }

    /**
     * Tenta aplicar a decisao automatica por maioria simples (2 de 3), se todas as
     * pre-condicoes estiverem satisfeitas:
     *   - Processo ainda em andamento (nao finalizado) e nao aguardando info;
     *   - Maioria formada (>= 2 favoraveis ou >= 2 desfavoraveis);
     *   - Nenhum parecer recebido sem o anexo comprobatorio (RESPOSTA_AVALIADOR /
     *     ou origem AVALIADOR_SISTEMA que dispensa o anexo).
     * Se todas as condicoes estiverem ok, chama {@link #decidir} e retorna o
     * processo atualizado. Caso contrario retorna o processo sem alteracao.
     * Deve ser chamado apos {@link #atualizarStatusPorPareceres} e apos
     * {@link #retomarAposInformacao}. Processo ja finalizado e erro de
     * programacao do caller (mesma razao de atualizarStatusPorPareceres).
     */
    @Transactional
    public Processo tentarDecisaoAutomatica(Long id) {
        Processo p = buscar(id);
        if (p.getStatus().isFinalizado()) {
            throw new IllegalStateException(ProcessoValidator.MSG_ENCERRADO);
        }
        // O coordenador CET-RS defere sozinho e imediatamente quando vota
        // Favoravel, mesmo que o processo esteja pausado por SOLICITA_INFORMACAO
        // por causa do parecer de outro avaliador. Essa prioridade so vale para
        // esse caminho automatico; sem o voto do coordenador, a pausa continua
        // bloqueando qualquer decisao automatica.
        if (p.getStatus() == StatusProcesso.SOLICITA_INFORMACAO && !temVotoCoordenadorFavoravel(p)) {
            return p;
        }
        Optional<StatusProcesso> sugestao = sugerirDecisao(p);
        if (sugestao.isEmpty()) {
            return p;
        }
        StatusProcesso decisao = sugestao.get();
        // INDEFERIDO NUNCA e automatico: a regra de negocio exige o MOTIVO do
        // indeferimento (que vai no oficio oficial ao solicitante) e so o
        // operador pode informa-lo. Um indeferimento automatico geraria um
        // oficio com "(motivo nao informado)". Por isso, quando a maioria e
        // desfavoravel, deixamos apenas a SUGESTAO e o operador confirma na aba
        // Decisao (onde o motivo e obrigatorio). So o DEFERIDO — que dispensa
        // motivo — e finalizado automaticamente aqui.
        if (decisao != StatusProcesso.DEFERIDO) {
            return p;
        }
        // So decide automaticamente se nao ha pareceres recebidos sem anexo
        if (!pareceresRecebidosSemAnexo(p).isEmpty()) {
            return p;
        }
        // Passa pela validacao completa (mesma do caminho manual) — defesa em
        // profundidade: nao grava um estado que decidir() rejeitaria.
        return decidir(id, StatusProcesso.DEFERIDO, null);
    }

    /**
     * Retoma a analise apos a chegada da informacao complementar do solicitante:
     * tira o processo de SOLICITA_INFORMACAO e o devolve para ENVIADO (fluxo de
     * Respostas/Decisao), para que o(s) avaliador(es) concluam o voto. Limpa o
     * voto "Solicita informacao" dos pareceres que o usaram, para que o medico
     * registre o parecer definitivo (favoravel/nao favoravel). Processo ja
     * finalizado e erro de programacao do caller (mesma razao de
     * atualizarStatusPorPareceres).
     */
    @Transactional
    public Processo retomarAposInformacao(Long id) {
        Processo p = buscar(id);
        if (p.getStatus().isFinalizado()) {
            throw new IllegalStateException(ProcessoValidator.MSG_ENCERRADO);
        }
        p.getPareceres().stream()
            .filter(par -> par.getResultado() == ResultadoParecer.SOLICITA_INFORMACAO)
            .forEach(par -> {
                // Reset COMPLETO para pendencia limpa: o parecer volta a ser uma
                // pendencia genuina, sem metadados obsoletos do voto "Solicita
                // informacao" antigo (preserva o nao-repudio do voto definitivo).
                // Mantem dataEnvio (o processo foi enviado de fato).
                par.setResultado(null);
                par.setDataResposta(null);
                par.setDataHoraVoto(null);
                par.setVotadoPor(null);
                par.setOrigem(null);
                par.setJustificativa(null);
            });
        p.setStatus(StatusProcesso.ENVIADO);
        return processoRepository.save(p);
    }

    /** True se algum avaliador pediu informacao complementar (parecer SOLICITA_INFORMACAO). */
    public boolean aguardandoInformacaoComplementar(Processo processo) {
        return processo.getStatus() == StatusProcesso.SOLICITA_INFORMACAO;
    }

    /** Atualiza apenas os dados descritivos do processo (numero e medicos nao mudam). */
    @Transactional
    public Processo atualizarDados(Long id, Processo form) {
        Processo p = buscar(id);
        // Defesa em profundidade: processo encerrado nao pode ser alterado
        // (o controller ja bloqueia; aqui garante que nenhum caminho escape).
        if (validator.edicaoBloqueada(p)) {
            throw new IllegalStateException(ProcessoValidator.MSG_ENCERRADO);
        }
        p.setPacienteNome(form.getPacienteNome());
        p.setPacienteRgct(form.getPacienteRgct());
        p.setSolicitanteEquipe(form.getSolicitanteEquipe());
        p.setSolicitanteEmail(form.getSolicitanteEmail());
        p.setDataSituacaoEspecial(form.getDataSituacaoEspecial());
        p.setObservacoes(form.getObservacoes());
        return processoRepository.save(p);
    }

    @Transactional
    public void excluir(Long id) {
        // Defesa em profundidade: apenas ADMIN pode excluir (OPERADOR edita,
        // mas nao exclui). O SecurityConfig ja barra a rota; aqui garante que
        // nenhum caminho de codigo escape mesmo se a rota for reconfigurada.
        exigirAdminParaExcluir();
        Processo p = buscar(id);
        // Defesa em profundidade: processo encerrado nao pode ser excluido
        // (o controller ja bloqueia; aqui garante que nenhum caminho escape).
        if (validator.edicaoBloqueada(p)) {
            throw new IllegalStateException(ProcessoValidator.MSG_ENCERRADO);
        }
        // Se o processo foi originado de uma SolicitacaoOnline (Portal do
        // Solicitante), essa linha ainda aponta pra ele via processo_gerado_id
        // (ManyToOne sem cascade/orphanRemoval a partir do Processo). Sem
        // desvincular primeiro, o DELETE do processo estoura violacao de FK.
        // A SolicitacaoOnline em si NAO e apagada - continua no historico do
        // portal, so perde o vinculo com o processo excluido; o status
        // (provavelmente CONVERTIDA) e mantido como esta, nao existe um valor
        // de StatusSolicitacaoOnline que reflita "processo excluido" e nao e
        // o caso de criar um agora.
        solicitacaoOnlineRepository.findByProcessoGeradoId(id).ifPresent(s -> {
            s.setProcessoGerado(null);
            solicitacaoOnlineRepository.save(s);
        });
        processoRepository.delete(p);
    }

    /** Lanca AccessDeniedException se o usuario autenticado nao for ADMIN. */
    private void exigirAdminParaExcluir() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean admin = auth != null && auth.getAuthorities().stream()
            .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (!admin) {
            throw new AccessDeniedException("Apenas administradores podem excluir processos.");
        }
    }

    // As consultas de contagem/sugestao/anexos foram centralizadas em
    // ProcessoValidator; o servico expoe os mesmos metodos delegando a ele.

    public long contarFavoraveis(Processo processo) {
        return validator.contarFavoraveis(processo);
    }

    public long contarNaoFavoraveis(Processo processo) {
        return validator.contarNaoFavoraveis(processo);
    }

    public long contarRespondidos(Processo processo) {
        return validator.contarRespondidos(processo);
    }

    public List<Parecer> pareceresRecebidosSemAnexo(Processo processo) {
        return validator.pareceresRecebidosSemAnexo(processo);
    }

    /**
     * Localiza o parecer de um processo especifico, garantindo que ele
     * pertence de fato a esse processo (evita um parecerId de outro processo
     * vazar por engano). Encapsula o acesso a {@link ParecerRepository} para
     * que os controllers nao precisem injeta-lo diretamente.
     */
    public Optional<Parecer> buscarParecer(Long processoId, Long parecerId) {
        return parecerRepository.findById(parecerId)
            .filter(par -> par.getProcesso().getId().equals(processoId));
    }

    /**
     * Pareceres pendentes (resultado nulo, envio ja registrado) de um processo
     * especifico - usado pelo lembrete manual de avaliacao pendente (individual
     * e em lote).
     */
    public List<Parecer> pareceresPendentesComEmail(Long processoId) {
        return parecerRepository.findByProcessoIdAndResultadoIsNullAndDataEnvioIsNotNull(processoId);
    }

    /** True se o processo esta encerrado e, portanto, com a edicao travada. */
    public boolean edicaoBloqueada(Processo processo) {
        return validator.edicaoBloqueada(processo);
    }

    public Optional<StatusProcesso> sugerirDecisao(Processo processo) {
        return validator.sugerirDecisao(processo);
    }

    public boolean temVotoCoordenadorFavoravel(Processo processo) {
        return validator.temVotoCoordenadorFavoravel(processo);
    }

    public boolean deferidoPeloCoordenador(Processo processo) {
        return validator.deferidoPeloCoordenador(processo);
    }

    public long favoraveisNecessariosParaDeferir(Processo processo) {
        return validator.favoraveisNecessariosParaDeferir(processo);
    }

    public long desfavoraveisNecessariosParaIndeferir() {
        return validator.desfavoraveisNecessariosParaIndeferir();
    }

    /** Registra a decisao final manual do servidor. */
    @Transactional
    public Processo decidir(Long id, StatusProcesso decisao, String motivoIndeferimento) {
        Processo p = buscar(id);
        // Processo ja encerrado nao pode ser redecidido sem antes reabrir (ADMIN).
        // O fluxo de reabertura volta o status para ENVIADO antes de redecidir.
        if (validator.edicaoBloqueada(p)) {
            throw new IllegalStateException(ProcessoValidator.MSG_ENCERRADO);
        }
        // Regras impostas em defesa (decidir() e publico e nao pode confiar apenas
        // na camada web): pausa por informacao complementar, votos suficientes,
        // motivo do indeferimento e anexo de toda resposta recebida. Centralizadas
        // em ProcessoValidator para nao divergirem das mesmas checagens no controller.
        validator.validarDecisao(p, decisao, motivoIndeferimento)
            .ifPresent(msg -> { throw new IllegalStateException(msg); });
        p.setStatus(decisao);
        p.setDataDecisao(LocalDateTime.now());
        if (decisao == StatusProcesso.INDEFERIDO) {
            p.setMotivoIndeferimento(motivoIndeferimento);
        }
        return processoRepository.save(p);
    }

    /**
     * Resultado de {@link #confirmarRespostaSolicitante}: o processo salvo e,
     * quando o envio automatico do e-mail falhou (SMTP fora do ar, processo
     * sem e-mail do solicitante cadastrado, etc.), um aviso NAO-BLOQUEANTE —
     * a confirmacao ja foi processada mesmo assim, pois a decisao clinica ja
     * foi tomada e uma falha de e-mail nao pode travar o fluxo.
     */
    public record ConfirmacaoRespostaResultado(Processo processo, String aviso) {
    }

    /**
     * Confirma (ou desmarca) o envio da resposta ao solicitante (aba 6). Ao
     * marcar como enviada, exige o comprovante que sustenta a decisao final —
     * SNT no Deferido, oficio no Indeferido (ProcessoValidator.
     * validarRespostaSolicitante) — mesma checagem usada na camada web, aqui
     * como defesa em profundidade: o metodo e publico e nao pode confiar
     * apenas no guard do controller. Essa exigencia regulatoria NAO muda.
     *
     * <p>Diferente do comportamento anterior (so marcava um checkbox), ao
     * confirmar com sucesso o metodo agora ENVIA DE VERDADE o e-mail de
     * resultado (Deferido/Indeferido) para {@code p.getSolicitanteEmail()},
     * com o comprovante SNT/oficio anexado quando disponivel em disco. Vale
     * para todo processo, inclusive os que nao vieram do Portal do
     * Solicitante (decisao confirmada com o usuario) — processos assim podem
     * ter um e-mail de solicitante de qualidade variavel (campo texto livre),
     * o que e aceitavel: uma falha de envio vira aviso, nao bloqueio.
     */
    @Transactional
    public ConfirmacaoRespostaResultado confirmarRespostaSolicitante(Long id, boolean emailEnviadoSolicitante) {
        Processo p = buscar(id);
        String aviso = null;
        if (emailEnviadoSolicitante) {
            validator.validarRespostaSolicitante(p)
                .ifPresent(msg -> { throw new IllegalStateException(msg); });
            aviso = enviarEmailResultadoFinal(p);
        }
        p.setEmailEnviadoSolicitante(emailEnviadoSolicitante);
        Processo salvo = processoRepository.save(p);
        return new ConfirmacaoRespostaResultado(salvo, aviso);
    }

    /**
     * Envia de fato o e-mail de resultado final (Deferido/Indeferido) ao
     * solicitante, anexando o comprovante SNT/oficio quando ja estiver salvo
     * em disco. Retorna {@code null} quando o envio foi bem sucedido, ou uma
     * mensagem de aviso (nao-bloqueante) quando nao foi possivel enviar —
     * falha de SMTP ou ausencia de e-mail do solicitante cadastrado nunca
     * impedem a confirmacao de ter sido processada.
     */
    private String enviarEmailResultadoFinal(Processo p) {
        String destinatario = p.getSolicitanteEmail();
        if (destinatario == null || destinatario.isBlank()) {
            auditoriaService.registrar("EMAIL_RESPOSTA_SOLICITANTE_FALHA",
                "Processo " + p.getNumero() + " - sem e-mail do solicitante cadastrado");
            return "Nao foi possivel enviar automaticamente o e-mail de resposta: o processo nao "
                + "tem e-mail do solicitante cadastrado. Envie manualmente pelo card de e-mails prontos.";
        }

        EmailTemplate template;
        TipoAnexo tipoAnexo;
        if (p.getStatus() == StatusProcesso.DEFERIDO) {
            template = emailTemplateService.emailDeferido(p);
            tipoAnexo = TipoAnexo.COMPROVANTE_SNT;
        } else if (p.getStatus() == StatusProcesso.INDEFERIDO) {
            template = emailTemplateService.emailIndeferido(p);
            tipoAnexo = TipoAnexo.OFICIO_INDEFERIMENTO;
        } else {
            // Nao deveria acontecer: validarRespostaSolicitante ja garante que
            // so chega aqui apos uma decisao final (Deferido/Indeferido).
            return null;
        }

        Anexo anexo = anexoStorageService.buscarUltimoPorTipo(p.getId(), tipoAnexo);
        boolean ok;
        if (anexo != null) {
            File arquivo = anexoStorageService.resolverArquivo(anexo).toFile();
            ok = emailSenderService.enviarComAnexo(
                destinatario, template.assunto(), template.corpo(), arquivo, anexo.getNomeArquivo());
        } else {
            // Nao deveria faltar (validarRespostaSolicitante ja exige o anexo),
            // mas manda o texto mesmo sem anexo em vez de bloquear a confirmacao.
            ok = emailSenderService.enviar(destinatario, template.assunto(), template.corpo());
        }

        if (ok) {
            auditoriaService.registrar("EMAIL_RESPOSTA_SOLICITANTE_ENVIADO",
                "Processo " + p.getNumero() + " - " + destinatario);
            return null;
        }
        log.warn("Falha ao enviar e-mail automatico de resposta ao solicitante do processo {}", p.getNumero());
        auditoriaService.registrar("EMAIL_RESPOSTA_SOLICITANTE_FALHA",
            "Processo " + p.getNumero() + " - " + destinatario);
        return "Nao foi possivel enviar automaticamente o e-mail de resposta ao solicitante (falha de "
            + "SMTP). A finalizacao foi confirmada mesmo assim; envie manualmente pelo card de e-mails prontos.";
    }

    /**
     * Reabre um processo ENCERRADO (Deferido/Indeferido/Cancelado), voltando-o
     * para {@link StatusProcesso#ENVIADO} para que o operador possa reavaliar e
     * decidir de novo. Limpa a decisao anterior (dataDecisao e motivo de
     * indeferimento); os pareceres sao mantidos como estao. Acao restrita ao
     * ADMIN (imposta no {@code SecurityConfig}). Lanca erro se o processo nao
     * estiver finalizado (nao ha o que reabrir).
     */
    @Transactional
    public Processo reabrir(Long id) {
        Processo p = buscar(id);
        if (!p.getStatus().isFinalizado()) {
            throw new IllegalStateException("So e possivel reabrir processos encerrados.");
        }
        p.setStatus(StatusProcesso.ENVIADO);
        p.setDataDecisao(null);
        p.setMotivoIndeferimento(null);
        return processoRepository.save(p);
    }

    private int extrairSequencial(String numero, int ano) {
        if (numero == null || numero.isBlank()) {
            return 0;
        }
        String parte = numero.split("/")[0].trim();
        try {
            return Integer.parseInt(parte);
        } catch (NumberFormatException e) {
            // fallback: proximo da sequencia do ano
            Integer max = processoRepository.findMaxSequencialByAno(ano);
            return (max == null ? 0 : max) + 1;
        }
    }
}
