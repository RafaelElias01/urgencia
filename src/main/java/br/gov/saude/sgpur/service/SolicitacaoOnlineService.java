package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.*;
import br.gov.saude.sgpur.repository.SolicitacaoOnlineRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Regras do modulo experimental "Solicitacao Online" (Portal do
 * Solicitante). Ver docs/PLANO-SOLICITANTE.md para o desenho completo.
 *
 * Deliberadamente NAO manipula {@link Processo}/{@link Parecer} diretamente
 * para criar um pedido - so ao converter, e delegando para
 * {@code ProcessoService.cadastrar} (chamado pelo controller de triagem, nao
 * por este servico), preservando 100% das regras de negocio do processo.
 */
@Service
public class SolicitacaoOnlineService {

    private static final Logger log = LoggerFactory.getLogger(SolicitacaoOnlineService.class);

    private final SolicitacaoOnlineRepository repository;
    private final AnexoSolicitacaoOnlineStorageService anexoStorage;
    private final AnexoStorageService anexoStorageProcesso;
    private final UsuarioRepository usuarioRepository;
    private final EmailSenderService emailSenderService;
    private final String baseUrl;

    public SolicitacaoOnlineService(SolicitacaoOnlineRepository repository,
                                    AnexoSolicitacaoOnlineStorageService anexoStorage,
                                    AnexoStorageService anexoStorageProcesso,
                                    UsuarioRepository usuarioRepository,
                                    EmailSenderService emailSenderService,
                                    @Value("${app.base-url:http://localhost:3000}") String baseUrl) {
        this.repository = repository;
        this.anexoStorage = anexoStorage;
        this.anexoStorageProcesso = anexoStorageProcesso;
        this.usuarioRepository = usuarioRepository;
        this.emailSenderService = emailSenderService;
        this.baseUrl = baseUrl;
    }

    public SolicitacaoOnline buscar(Long id) {
        return repository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Solicitacao online nao encontrada: " + id));
    }

    /**
     * Versao de {@link #buscar(Long)} para as TELAS DE DETALHE (portal do
     * solicitante e triagem do operador), que renderizam anexos e o processo
     * gerado. Com {@code open-in-view: false} o template roda fora da
     * transacao, entao as associacoes precisam vir carregadas daqui - senao da
     * {@code LazyInitializationException} e o usuario ve 500.
     */
    public SolicitacaoOnline buscarParaDetalhe(Long id) {
        return repository.findParaDetalhe(id)
            .orElseThrow(() -> new IllegalArgumentException("Solicitacao online nao encontrada: " + id));
    }

    public List<SolicitacaoOnline> listarMinhas(Long usuarioSolicitanteId) {
        return repository.findMinhasParaLista(usuarioSolicitanteId);
    }

    /**
     * Verdadeiro quando o pedido ja virou {@link Processo} e esse processo
     * esta pausado aguardando informacao complementar de um avaliador
     * (ver regra "Solicita informacao (PAUSA)" no CLAUDE.md). Usado para
     * decidir se o Portal do Solicitante mostra o formulario de upload
     * direto de informacao complementar.
     */
    public boolean precisaInformacaoComplementar(SolicitacaoOnline s) {
        return s.getStatus() == StatusSolicitacaoOnline.CONVERTIDA
            && s.getProcessoGerado() != null
            && s.getProcessoGerado().getStatus() == StatusProcesso.SOLICITA_INFORMACAO;
    }

    /**
     * Verdadeiro se o solicitante ja enviou a informacao complementar desta
     * rodada de pausa (o processo pode entrar em SOLICITA_INFORMACAO mais de
     * uma vez ao longo da vida). O inicio da rodada atual e o maior
     * {@code Parecer.dataHoraVoto} entre os pareceres com resultado
     * SOLICITA_INFORMACAO (setado no voto autenticado do Portal do
     * Avaliador); qualquer anexo INFO_COMPLEMENTAR enviado depois desse
     * instante ja e desta rodada. Usado pro Portal do Solicitante esconder o
     * formulario apos o primeiro envio, ate o operador retomar a analise.
     */
    public boolean jaEnviouInformacaoComplementarNestaRodada(SolicitacaoOnline s) {
        if (!precisaInformacaoComplementar(s)) {
            return false;
        }
        Processo processo = s.getProcessoGerado();
        LocalDateTime inicioRodada = processo.getPareceres().stream()
            .filter(par -> par.getResultado() == ResultadoParecer.SOLICITA_INFORMACAO)
            .map(Parecer::getDataHoraVoto)
            .filter(java.util.Objects::nonNull)
            .max(LocalDateTime::compareTo)
            .orElse(null);
        return processo.getAnexos().stream()
            .anyMatch(a -> a.getTipo() == TipoAnexo.INFO_COMPLEMENTAR
                && (inicioRodada == null || a.getDataUpload().isAfter(inicioRodada)));
    }

    /**
     * Recebe o(s) arquivo(s) de informacao complementar enviados pelo
     * SOLICITANTE diretamente no portal, como alternativa ao e-mail externo.
     * So grava o anexo {@code TipoAnexo.INFO_COMPLEMENTAR} no {@link Processo}
     * - quem decide retomar a analise continua sendo exclusivamente o
     * OPERADOR via {@code ProcessoService.retomarAposInformacao}
     * (este metodo NUNCA muda o status do processo).
     *
     * Revalida o estado aqui dentro (defesa em profundidade, mesmo padrao de
     * {@code ProcessoService.decidir}): cobre a corrida em que o operador ja
     * retomou a analise entre a tela abrir e o solicitante enviar.
     */
    @Transactional
    public void enviarInformacaoComplementar(SolicitacaoOnline s, List<MultipartFile> arquivos) {
        if (!precisaInformacaoComplementar(s)) {
            throw new IllegalStateException(
                "Este pedido nao esta aguardando informacao complementar no momento.");
        }
        if (jaEnviouInformacaoComplementarNestaRodada(s)) {
            throw new IllegalStateException(
                "Voce ja enviou as informacoes complementares para esta solicitacao. "
                + "Aguarde a analise da equipe de Urgencia Renal.");
        }
        boolean algumArquivo = arquivos != null && arquivos.stream().anyMatch(a -> a != null && !a.isEmpty());
        if (!algumArquivo) {
            throw new IllegalArgumentException("Anexe pelo menos um arquivo.");
        }
        for (MultipartFile arquivo : arquivos) {
            if (arquivo == null || arquivo.isEmpty()) {
                continue;
            }
            try {
                anexoStorageProcesso.salvar(s.getProcessoGerado(), TipoAnexo.INFO_COMPLEMENTAR,
                    "Resposta com informacoes complementares enviada pelo solicitante via Portal do Solicitante",
                    arquivo);
            } catch (IOException e) {
                // IOException e checked - sem envolver numa RuntimeException, o Spring
                // NAO faz rollback (so reverte @Transactional em RuntimeException/Error
                // por padrao) e os anexos ja salvos neste loop ficariam commitados
                // mesmo com a falha. Mesmo padrao ja usado em criar() (acima).
                throw new IllegalStateException(
                    "Falha ao salvar arquivo enviado: " + e.getMessage(), e);
            }
        }
    }

    public List<SolicitacaoOnline> listarPendentesTriagem() {
        return repository.findByStatusOrderByDataEnvioAsc(StatusSolicitacaoOnline.ENVIADA);
    }

    /** Contagem de pendentes de triagem, para o badge da navbar - evita carregar a lista inteira. */
    public long contarPendentesTriagem() {
        return repository.countByStatus(StatusSolicitacaoOnline.ENVIADA);
    }

    /** Todas as solicitacoes, qualquer status, mais recentes primeiro (aba "Todas" da triagem). */
    public List<SolicitacaoOnline> listarTodas() {
        return repository.findAllByOrderByDataEnvioDesc();
    }

    /**
     * Dias corridos desde o envio, com a classe de cor Bootstrap para
     * destacar espera longa na fila de triagem (formatacao pronta aqui, nunca
     * calculada na view - mesmo padrao de {@code TempoRespostaService}).
     * Limiares: acima de 7 dias = alerta (vermelho), acima de 3 = atencao
     * (amarelo), caso contrario neutro.
     */
    public DiasEspera diasEspera(SolicitacaoOnline s) {
        long dias = java.time.Duration.between(s.getDataEnvio(), LocalDateTime.now()).toDays();
        String cssClass = dias > 7 ? "bg-danger" : dias > 3 ? "bg-warning text-dark" : "bg-secondary";
        return new DiasEspera(dias, cssClass);
    }

    /** Dias de espera + classe de cor Bootstrap pronta para o badge (ver {@link #diasEspera}). */
    public record DiasEspera(long dias, String badgeClass) {
    }

    /**
     * Contagem por status de uma lista de solicitacoes ja carregada (ex.: as
     * do proprio solicitante em {@code listarMinhas}) - usado nos cards de
     * resumo do Portal do Solicitante. Calculo puro em memoria, sem query
     * adicional, para nao acoplar a tela a uma nova consulta ao banco.
     */
    public Resumo resumir(List<SolicitacaoOnline> solicitacoes) {
        long aguardandoTriagem = 0;
        long emAnalise = 0;
        long decididas = 0;
        long devolvidas = 0;
        for (SolicitacaoOnline s : solicitacoes) {
            switch (s.getStatus()) {
                case ENVIADA -> aguardandoTriagem++;
                case CONVERTIDA -> {
                    if (s.getProcessoGerado() != null && s.getProcessoGerado().getStatus().isFinalizado()) {
                        decididas++;
                    } else {
                        emAnalise++;
                    }
                }
                case APROVADA, REPROVADA, CANCELADA, PROCESSO_EXCLUIDO -> decididas++;
                case DEVOLVIDA -> devolvidas++;
            }
        }
        return new Resumo(solicitacoes.size(), aguardandoTriagem, emAnalise, decididas, devolvidas);
    }

    /** Resumo por status para os cards de estatistica da tela "Minhas solicitacoes". */
    public record Resumo(long total, long aguardandoTriagem, long emAnalise, long decididas, long devolvidas) {
    }

    /**
     * Cria uma nova solicitacao (status ENVIADA) e anexa os documentos
     * clinicos enviados junto. Equipe/e-mail do solicitante SEMPRE vem do
     * {@code Usuario} logado (nunca do formulario) - evita que o solicitante
     * se declare de outra equipe.
     *
     * <p><b>Nao ha copia campo a campo aqui</b> (ao contrario de
     * {@code UsuarioService.atualizar} / {@code ControleUrgenciaService.atualizar}):
     * a propria entidade e o objeto do {@code @ModelAttribute}, entao os campos
     * do formulario {@code solicitante/nova.html} - {@code pacienteNome},
     * {@code pacienteRgct}, {@code dataSituacaoEspecial},
     * {@code justificativaClinica} - ja chegam preenchidos e sao persistidos
     * sem intermediario. Nao existe, por construcao, o risco de "esquecer de
     * copiar um campo novo do formulario" nesse caminho.
     *
     * <p><b>Sobrescritos DE PROPOSITO</b> logo abaixo (defesa contra mass
     * assignment - nao remover achando que e bug): {@code id} (forcado a null,
     * senao um POST com id sequestraria a solicitacao de outro),
     * {@code usuarioSolicitante} / {@code solicitanteEquipe} /
     * {@code solicitanteEmail} (vem do usuario logado), {@code status}
     * (sempre ENVIADA), {@code processoGerado} e {@code observacoesTriagem}
     * (so a triagem preenche) e {@code dataEnvio} (ordena a fila de triagem;
     * precisa ser o instante real do envio). {@code SolicitacaoOnline} nao tem
     * nenhum metodo de EDICAO de campos apos o envio - o solicitante so pode
     * cancelar enquanto nao triado, e a triagem so muda status/observacoes.
     */
    @Transactional
    public SolicitacaoOnline criar(SolicitacaoOnline solicitacao, Usuario usuarioLogado,
                                   List<MultipartFile> documentos) {
        if (usuarioLogado.getEquipeSolicitante() == null || usuarioLogado.getEquipeSolicitante().isBlank()) {
            throw new IllegalStateException(
                "Usuario solicitante sem equipe vinculada. Contate o administrador.");
        }
        solicitacao.setId(null);
        solicitacao.setUsuarioSolicitante(usuarioLogado);
        solicitacao.setSolicitanteEquipe(usuarioLogado.getEquipeSolicitante());
        solicitacao.setSolicitanteEmail(usuarioLogado.getEmail());
        solicitacao.setStatus(StatusSolicitacaoOnline.ENVIADA);
        solicitacao.setProcessoGerado(null);
        solicitacao.setObservacoesTriagem(null);
        // Nunca confia no dataEnvio vindo do formulario (o binding do @ModelAttribute
        // poderia receber um valor forjado) - a fila de triagem ordena por esta data,
        // entao ela precisa refletir o momento real do envio.
        solicitacao.setDataEnvio(LocalDateTime.now());
        SolicitacaoOnline salva = repository.save(solicitacao);

        if (documentos != null) {
            for (MultipartFile arquivo : documentos) {
                if (arquivo == null || arquivo.isEmpty()) {
                    continue;
                }
                try {
                    salva.addAnexo(anexoStorage.salvar(salva, arquivo));
                } catch (IOException e) {
                    throw new IllegalStateException("Falha ao salvar documento anexado: " + e.getMessage(), e);
                }
            }
        }
        notificarOperadores(salva);
        return salva;
    }

    /**
     * Avisa ADMIN/OPERADOR ativos (com e-mail cadastrado) que ha uma nova
     * solicitacao aguardando triagem. Best-effort: falha de envio so gera log,
     * nunca impede a solicitacao de ser criada (o pedido do solicitante ja
     * foi salvo com sucesso nesse ponto).
     */
    private void notificarOperadores(SolicitacaoOnline s) {
        List<Usuario> destinatarios = usuarioRepository
            .findByPerfilInAndAtivoTrue(List.of(Perfil.ADMIN, Perfil.OPERADOR));
        String[] emails = destinatarios.stream()
            .map(Usuario::getEmail)
            .filter(e -> e != null && !e.isBlank())
            .distinct()
            .toArray(String[]::new);
        if (emails.length == 0) {
            log.warn("SolicitacaoOnlineService: nenhum ADMIN/OPERADOR com e-mail cadastrado - "
                + "notificacao da solicitacao {} nao enviada.", s.getId());
            return;
        }
        String corpo = """
            Uma nova solicitacao de urgencia renal foi enviada pelo Portal do Solicitante.

            Paciente: %s
            RGCT/SNT: %s
            Equipe solicitante: %s

            Acesse a fila de triagem para revisar e prosseguir com o cadastro do processo:
            %s/processos/solicitacoes-online/%d
            """.formatted(s.getPacienteNome(), s.getPacienteRgct(), s.getSolicitanteEquipe(),
                baseUrl, s.getId());
        emailSenderService.enviar(emails, null,
            "Nova solicitacao online aguardando triagem - " + s.getPacienteNome(), corpo);
    }

    /**
     * Cancela a propria solicitacao, apenas enquanto ainda nao foi triada
     * (status ENVIADA). Verifica posse (o dono precisa ser quem cancela).
     */
    @Transactional
    public void cancelar(Long id, Long usuarioLogadoId) {
        SolicitacaoOnline s = buscar(id);
        if (!s.getUsuarioSolicitante().getId().equals(usuarioLogadoId)) {
            throw new IllegalStateException("Voce so pode cancelar as suas proprias solicitacoes.");
        }
        if (s.getStatus() != StatusSolicitacaoOnline.ENVIADA) {
            throw new IllegalStateException(
                "So e possivel cancelar solicitacoes que ainda nao foram triadas pelo operador.");
        }
        s.setStatus(StatusSolicitacaoOnline.CANCELADA);
        repository.save(s);
    }

    /**
     * Devolve a solicitacao ao solicitante para correcao (ex.: dado
     * incompleto, documento faltando), registrando o motivo.
     */
    @Transactional
    public void devolver(Long id, String observacoes) {
        SolicitacaoOnline s = buscar(id);
        if (s.getStatus() != StatusSolicitacaoOnline.ENVIADA) {
            throw new IllegalStateException("Esta solicitacao ja foi triada.");
        }
        s.setStatus(StatusSolicitacaoOnline.DEVOLVIDA);
        s.setObservacoesTriagem(observacoes);
        repository.save(s);
    }

    /**
     * Marca a solicitacao como convertida no processo informado e copia os
     * documentos clinicos anexados para o processo real.
     *
     * <p><b>Trava de anonimizacao:</b> a copia entra como
     * {@code TipoAnexo.DOCUMENTO_PORTAL_NAO_ANONIMIZADO} (staging), NUNCA como
     * {@code DOCUMENTO_CLINICO_AVALIADOR}. O documento do solicitante traz o
     * nome completo do paciente impresso no corpo do laudo; se entrasse direto
     * como material do avaliador, bastaria o operador clicar em "Registrar
     * envio" sem revisar nada para os 3 medicos receberem o nome do paciente,
     * quebrando a regra de imparcialidade (e o estado final ficaria
     * indistinguivel do fluxo correto). O tipo de staging nao entra no PDF
     * consolidado nem satisfaz {@code ProcessoValidator.validarRegistroEnvio}:
     * so vira material do avaliador por confirmacao explicita e auditada do
     * operador (ver {@code ProcessoDetalheController.confirmarAnonimizacao}).
     *
     * <p><b>Processos convertidos ANTES desta trava</b> gravaram o anexo do
     * portal como {@code DOCUMENTO_CLINICO_AVALIADOR}. Nada muda para eles: o
     * tipo antigo continua valido, elegivel ao merge e suficiente para
     * registrar o envio - a trava vale apenas para conversoes novas.
     *
     * Chamado pelo controller de triagem DEPOIS que
     * {@code ProcessoService.cadastrar} ja rodou com sucesso (numero
     * atribuido, 3 avaliadores escolhidos pelo operador) - este metodo nunca
     * cria nem altera o {@code Processo} em si.
     */
    @Transactional
    public void converter(Long id, Processo processoGerado) {
        SolicitacaoOnline s = buscar(id);
        if (s.getStatus() != StatusSolicitacaoOnline.ENVIADA) {
            throw new IllegalStateException("Esta solicitacao ja foi triada.");
        }
        for (AnexoSolicitacaoOnline anexo : s.getAnexos()) {
            try {
                byte[] dados = Files.readAllBytes(anexoStorage.resolverArquivo(anexo));
                anexoStorageProcesso.salvarBytes(processoGerado, TipoAnexo.DOCUMENTO_PORTAL_NAO_ANONIMIZADO,
                    "Documento enviado pelo solicitante no Portal do Solicitante - NAO ANONIMIZADO: "
                        + "revisar e anonimizar o corpo (nome do paciente) e confirmar a anonimizacao "
                        + "na aba Envio antes de enviar aos avaliadores",
                    anexo.getNomeArquivo(), anexo.getContentType(), dados);
            } catch (IOException e) {
                throw new IllegalStateException(
                    "Falha ao copiar documento '" + anexo.getNomeArquivo() + "' para o processo: " + e.getMessage(), e);
            }
        }
        s.setStatus(StatusSolicitacaoOnline.CONVERTIDA);
        s.setProcessoGerado(processoGerado);
        repository.save(s);
    }
}
