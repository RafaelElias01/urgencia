package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.*;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.ProcessoRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    private final ProcessoRepository processoRepository;
    private final MembroUrgenciaRenalRepository membroRepository;
    private final ProcessoValidator validator;

    public ProcessoService(ProcessoRepository processoRepository,
                           MembroUrgenciaRenalRepository membroRepository,
                           ProcessoValidator validator) {
        this.processoRepository = processoRepository;
        this.membroRepository = membroRepository;
        this.validator = validator;
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
        // decisao registrada.
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
     * Processos ja finalizados (DEFERIDO/INDEFERIDO/CANCELADO) nao sao tocados.
     */
    @Transactional
    public Processo atualizarStatusPorPareceres(Long id) {
        Processo p = buscar(id);
        if (p.getStatus().isFinalizado()) {
            return p;
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
     * {@link #retomarAposInformacao}.
     */
    @Transactional
    public Processo tentarDecisaoAutomatica(Long id) {
        Processo p = buscar(id);
        if (p.getStatus().isFinalizado()) {
            return p;
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
     * registre o parecer definitivo (favoravel/nao favoravel). Nao toca em
     * processos ja finalizados.
     */
    @Transactional
    public Processo retomarAposInformacao(Long id) {
        Processo p = buscar(id);
        if (p.getStatus().isFinalizado()) {
            return p;
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
