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

    public List<SolicitacaoOnline> listarMinhas(Long usuarioSolicitanteId) {
        return repository.findByUsuarioSolicitanteIdOrderByDataEnvioDesc(usuarioSolicitanteId);
    }

    public List<SolicitacaoOnline> listarPendentesTriagem() {
        return repository.findByStatusOrderByDataEnvioAsc(StatusSolicitacaoOnline.ENVIADA);
    }

    /**
     * Cria uma nova solicitacao (status ENVIADA) e anexa os documentos
     * clinicos enviados junto. Equipe/e-mail do solicitante SEMPRE vem do
     * {@code Usuario} logado (nunca do formulario) - evita que o solicitante
     * se declare de outra equipe.
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
     * documentos clinicos anexados para o processo real, como
     * {@code TipoAnexo.DOCUMENTO_CLINICO_AVALIADOR} - candidatos que o
     * operador ainda revisa/anonimiza normalmente no Passo 2 (Envio), igual
     * a qualquer outro documento clinico anexado manualmente.
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
                anexoStorageProcesso.salvarBytes(processoGerado, TipoAnexo.DOCUMENTO_CLINICO_AVALIADOR,
                    "Documento clinico enviado pelo solicitante no Portal do Solicitante",
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
