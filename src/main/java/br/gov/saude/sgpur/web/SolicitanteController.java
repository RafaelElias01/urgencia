package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.Anexo;
import br.gov.saude.sgpur.domain.AnexoSolicitacaoOnline;
import br.gov.saude.sgpur.domain.MensagemSolicitacao;
import br.gov.saude.sgpur.domain.SolicitacaoOnline;
import br.gov.saude.sgpur.domain.StatusSolicitacaoOnline;
import br.gov.saude.sgpur.domain.TipoAnexo;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.repository.AnexoSolicitacaoOnlineRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import br.gov.saude.sgpur.service.AnexoSolicitacaoOnlineStorageService;
import br.gov.saude.sgpur.service.AnexoStorageService;
import br.gov.saude.sgpur.service.AuditoriaService;
import br.gov.saude.sgpur.service.MensagemSolicitacaoService;
import br.gov.saude.sgpur.service.SolicitacaoOnlineService;
import br.gov.saude.sgpur.service.TempoRespostaService;
import br.gov.saude.sgpur.domain.StatusProcesso;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.net.MalformedURLException;
import java.nio.file.Path;
import java.security.Principal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Portal do Solicitante — modulo experimental e OPCIONAL (ver
 * docs/PLANO-SOLICITANTE.md). Permite que a equipe solicitante envie o
 * pedido de urgencia renal direto pelo sistema, como alternativa ao fluxo
 * por e-mail. O solicitante NAO envia para avaliacao, NAO escolhe os
 * medicos avaliadores e NAO gera o material anonimizado — tudo isso
 * continua exclusivo do OPERADOR, que faz a triagem em
 * {@code /processos/solicitacoes-online} e converte o pedido num
 * {@code Processo} de verdade.
 *
 * Desligado por padrao em producao ({@code app.solicitante.habilitado}) —
 * quando desligado, este controller nem e registrado e as rotas /solicitante/**
 * respondem 404.
 *
 * <p><b>Sem @Transactional de nivel de classe (removido em 2026-07-29).</b> Essa
 * anotacao fazia os metodos POST com try/catch (ex.: {@link #criar},
 * {@link #cancelar}, {@link #enviarInformacaoComplementar},
 * {@link #apagarMensagem}/{@link #apagarMensagemAjax}) compartilharem a MESMA
 * transacao fisica com os servicos {@code @Transactional} chamados dentro do
 * try: uma {@code IllegalStateException}/{@code IllegalArgumentException} de
 * negocio lancada la dentro marcava a transacao inteira como rollback-only, o
 * {@code catch} tratava o erro normalmente e devolvia um flash amigavel, mas o
 * commit no fim do metodo do controller estourava
 * {@code UnexpectedRollbackException} (500 cru em vez do flash esperado) —
 * mesma familia de bug ja corrigida em {@code AvaliadorController} (voto do
 * avaliador perdido) e em {@code ProcessoDecisaoController.finalizar}. Cada
 * metodo agora declara sua propria estrategia de transacao, documentada no
 * javadoc do metodo: leitura pura ({@code readOnly = true}), leitura-escrita
 * simples quando nao ha try/catch em volta de escrita, ou
 * {@code NOT_SUPPORTED} quando ha um unico ponto de escrita relevante dentro
 * de um try/catch (o servico chamado abre sua propria transacao curta e
 * independente). Nenhum acesso LAZY problematico foi encontrado neste
 * controller: {@link SolicitacaoOnline#getUsuarioSolicitante()} e
 * {@link SolicitacaoOnline#getProcessoGerado()} sao {@code LAZY}, mas
 * {@code buscarParaDetalhe}/{@code findMinhasParaLista} ja fazem fetch join
 * de ambos, e os poucos usos de {@code buscar()} (sem fetch join) so leem
 * {@code getId()} de {@code usuarioSolicitante} — seguro mesmo com o proxy
 * fechado (Hibernate nao precisa de sessao aberta so para o identificador).
 */
@Controller
@RequestMapping("/solicitante")
@ConditionalOnProperty(prefix = "app.solicitante", name = "habilitado", havingValue = "true", matchIfMissing = true)
public class SolicitanteController {

    private final UsuarioRepository usuarioRepo;
    private final SolicitacaoOnlineService solicitacaoService;
    private final AuditoriaService auditoria;
    private final AnexoSolicitacaoOnlineRepository anexoRepo;
    private final AnexoSolicitacaoOnlineStorageService anexoStorage;
    private final AnexoStorageService anexoStorageProcesso;
    private final MensagemSolicitacaoService mensagemService;
    private final TempoRespostaService tempoRespostaService;

    public SolicitanteController(UsuarioRepository usuarioRepo,
                                 SolicitacaoOnlineService solicitacaoService,
                                 AuditoriaService auditoria,
                                 AnexoSolicitacaoOnlineRepository anexoRepo,
                                 AnexoSolicitacaoOnlineStorageService anexoStorage,
                                 AnexoStorageService anexoStorageProcesso,
                                 MensagemSolicitacaoService mensagemService,
                                 TempoRespostaService tempoRespostaService) {
        this.usuarioRepo = usuarioRepo;
        this.solicitacaoService = solicitacaoService;
        this.auditoria = auditoria;
        this.anexoStorageProcesso = anexoStorageProcesso;
        this.anexoRepo = anexoRepo;
        this.anexoStorage = anexoStorage;
        this.mensagemService = mensagemService;
        this.tempoRespostaService = tempoRespostaService;
    }

    /**
     * Allowlist explicita dos campos que o solicitante pode preencher no
     * formulario de nova solicitacao. Sem isso, o binding de
     * {@code @ModelAttribute("solicitacao") SolicitacaoOnline} aceitaria
     * QUALQUER campo da entidade presente no request (mass assignment) -
     * incluindo {@code id}, {@code status}, {@code usuarioSolicitante},
     * {@code processoGerado} etc, que {@link SolicitacaoOnlineService#criar}
     * ja reseta explicitamente, mas e mais seguro nunca deixar o bind tocar
     * neles em primeiro lugar (defesa em profundidade).
     */
    @InitBinder("solicitacao")
    public void initBinderSolicitacao(WebDataBinder binder) {
        binder.setAllowedFields(
            "pacienteNome", "pacienteRgct", "dataSituacaoEspecial", "justificativaClinica");
    }

    @GetMapping
    @Transactional(readOnly = true)
    public String lista(Principal principal, Model model) {
        Usuario usuario = resolverUsuario(principal);
        List<SolicitacaoOnline> minhas = solicitacaoService.listarMinhas(usuario.getId());
        model.addAttribute("solicitacoes", minhas);
        model.addAttribute("resumo", solicitacaoService.resumir(minhas));
        Map<Long, SolicitacaoOnlineService.DiasEspera> diasEspera = new LinkedHashMap<>();
        Map<Long, Boolean> acaoNecessaria = new LinkedHashMap<>();
        Map<Long, Boolean> mensagensNaoLidas = new LinkedHashMap<>();
        for (SolicitacaoOnline s : minhas) {
            if (s.getStatus() == StatusSolicitacaoOnline.ENVIADA) {
                diasEspera.put(s.getId(), solicitacaoService.diasEspera(s));
            }
            acaoNecessaria.put(s.getId(), solicitacaoService.precisaInformacaoComplementar(s));
            mensagensNaoLidas.put(s.getId(),
                mensagemService.contarNaoLidasSolicitantePorSolicitacao(s.getId(), usuario.getId()) > 0);
        }
        long totalAcaoNecessaria = acaoNecessaria.values().stream().filter(Boolean::booleanValue).count();
        model.addAttribute("diasEspera", diasEspera);
        model.addAttribute("acaoNecessaria", acaoNecessaria);
        model.addAttribute("totalAcaoNecessaria", totalAcaoNecessaria);
        model.addAttribute("mensagensNaoLidas", mensagensNaoLidas);
        model.addAttribute("equipe", usuario.getEquipeSolicitante());
        return "solicitante/lista";
    }

    @GetMapping("/nova")
    @Transactional(readOnly = true)
    public String nova(Principal principal, Model model) {
        Usuario usuario = resolverUsuario(principal);
        SolicitacaoOnline s = new SolicitacaoOnline();
        s.setDataSituacaoEspecial(LocalDate.now());
        model.addAttribute("solicitacao", s);
        model.addAttribute("equipe", usuario.getEquipeSolicitante());
        model.addAttribute("email", usuario.getEmail());
        return "solicitante/nova";
    }

    /**
     * NOT_SUPPORTED: unica escrita relevante e {@code solicitacaoService.criar(...)},
     * ja {@code @Transactional} no proprio servico. Sem transacao de classe
     * para "envenenar" quando o servico lanca ({@code IllegalArgumentException}/
     * {@code IllegalStateException}), o catch abaixo devolve o formulario com
     * flash de erro normalmente, sem risco de {@code UnexpectedRollbackException}
     * no commit (nao ha commit de controller nenhum a fazer).
     */
    @PostMapping("/nova")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public String criar(@ModelAttribute("solicitacao") SolicitacaoOnline solicitacao,
                        @RequestParam(value = "documentos", required = false) List<MultipartFile> documentos,
                        Principal principal, Model model, RedirectAttributes ra) {
        Usuario usuario = resolverUsuario(principal);
        try {
            SolicitacaoOnline salva = solicitacaoService.criar(solicitacao, usuario, documentos);
            auditoria.registrar("SOLICITACAO_ONLINE_ENVIADA",
                "Solicitacao " + salva.getId() + " - " + salva.identificacao());
            ra.addFlashAttribute("msg",
                "Solicitacao enviada. Aguarde a triagem da equipe de Urgencia Renal.");
            return "redirect:/solicitante";
        } catch (IllegalArgumentException | IllegalStateException e) {
            model.addAttribute("equipe", usuario.getEquipeSolicitante());
            model.addAttribute("email", usuario.getEmail());
            model.addAttribute("erro", e.getMessage());
            return "solicitante/nova";
        }
    }

    @GetMapping("/{id}")
    @Transactional
    public String detalhe(@PathVariable Long id, Principal principal, Model model) {
        Usuario usuario = resolverUsuario(principal);
        SolicitacaoOnline s = conferirPosse(solicitacaoService.buscarParaDetalhe(id), usuario);
        model.addAttribute("solicitacao", s);
        // Fonte unica da regra: o botao aparece exatamente quando cancelar()
        // aceitaria (ver SolicitacaoOnlineService.podeCancelar).
        model.addAttribute("podeCancelar", solicitacaoService.podeCancelar(s));
        model.addAttribute("diasEspera", solicitacaoService.diasEspera(s));
        model.addAttribute("precisaInformacaoComplementar", solicitacaoService.precisaInformacaoComplementar(s));
        model.addAttribute("jaEnviouInfoComplementar", solicitacaoService.jaEnviouInformacaoComplementarNestaRodada(s));
        // Previsao de prazo (so faz sentido enquanto os avaliadores estao de fato
        // analisando - nao antes da triagem, nem pausado em "Solicita informacao"
        // (aguardando o proprio solicitante), nem depois de decidido). Baseada na
        // media historica real dos avaliadores (mesmo indicador de /membros),
        // nao uma promessa de prazo formal - so uma referencia pro solicitante.
        boolean emAnaliseAtiva = s.getProcessoGerado() != null
            && s.getProcessoGerado().getStatus() == StatusProcesso.ENVIADO;
        model.addAttribute("previsaoPrazo", emAnaliseAtiva
            ? TempoRespostaService.formatarDias(tempoRespostaService.calcular().mediaGeralDias())
            : null);
        if (s.getProcessoGerado() != null) {
            model.addAttribute("comprovanteSntAnexo",
                anexoStorageProcesso.buscarUltimoPorTipo(s.getProcessoGerado().getId(), TipoAnexo.COMPROVANTE_SNT));
            model.addAttribute("oficioIndeferimentoAnexo",
                anexoStorageProcesso.buscarUltimoPorTipo(s.getProcessoGerado().getId(), TipoAnexo.OFICIO_INDEFERIMENTO));
        }
        List<MensagemSolicitacao> mensagens = mensagemService.listarPorSolicitacao(id);
        model.addAttribute("mensagens", mensagens);
        long msgNaoLidas = mensagens.stream()
            .filter(m -> !m.isLida() && m.getRemetente() == MensagemSolicitacao.RemetenteMensagem.OPERADOR
                && !m.getRemetenteId().equals(usuario.getId()))
            .count();
        model.addAttribute("msgNaoLidas", msgNaoLidas);
        // Evita notificacao duplicada: esta tela ja tem seu proprio poll de chat
        // (chat-solicitacao.js), entao o poll GLOBAL da navbar (layout.html) fica
        // desligado aqui.
        model.addAttribute("chatAtivoNestaTela", true);
        mensagemService.marcarComoLidas(id, MensagemSolicitacao.RemetenteMensagem.OPERADOR, usuario.getId());
        return "solicitante/detalhe";
    }

    /**
     * Contagem global de respostas do operador ainda nao lidas (somando
     * todas as solicitacoes deste solicitante), pra notificacao
     * sonora/toast em QUALQUER tela do portal (layout.html) - a tela de
     * detalhe (/solicitante/{id}) ja tem seu proprio poll especifico e nao
     * usa este endpoint (ver chatAtivoNestaTela).
     */
    @GetMapping("/nao-lidas-count")
    @ResponseBody
    @Transactional(readOnly = true)
    public Map<String, Object> naoLidasCount(Principal principal) {
        Usuario usuario = resolverUsuario(principal);
        return Map.of("total", mensagemService.contarNaoLidasParaSolicitante(usuario.getId()));
    }

    /**
     * Upload direto, pelo solicitante, dos documentos/dados pedidos por um
     * avaliador quando o processo esta pausado (SOLICITA_INFORMACAO). O
     * solicitante SO ENVIA - quem decide retomar a analise continua sendo o
     * OPERADOR (POST /processos/{id}/retomar-analise), nunca este endpoint.
     *
     * <p><b>NOT_SUPPORTED:</b> unica escrita relevante e
     * {@code solicitacaoService.enviarInformacaoComplementar(...)}, ja
     * {@code @Transactional} no servico. A leitura anterior
     * ({@code buscarParaDetalhe}) roda na sua propria transacao independente
     * (fetch join de anexos/processoGerado/usuarioSolicitante, sem depender de
     * sessao aberta neste metodo). Sem isso, uma falha de negocio dentro do
     * servico (ex.: pausa ja retomada pelo operador) marcaria a transacao de
     * classe como rollback-only e o catch abaixo devolveria o flash de erro
     * as custas de um {@code UnexpectedRollbackException} no commit.
     */
    @PostMapping("/{id}/informacao-complementar")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public String enviarInformacaoComplementar(@PathVariable Long id,
            @RequestParam(value = "arquivos", required = false) List<MultipartFile> arquivos,
            Principal principal, RedirectAttributes ra) {
        Usuario usuario = resolverUsuario(principal);
        SolicitacaoOnline s = conferirPosse(solicitacaoService.buscarParaDetalhe(id), usuario);
        try {
            solicitacaoService.enviarInformacaoComplementar(s, arquivos);
            auditoria.registrar("INFO_COMPLEMENTAR_RECEBIDA_PORTAL",
                "Solicitacao " + id + " - processo " + s.getProcessoGerado().getNumero());
            ra.addFlashAttribute("msg",
                "Informacoes enviadas. A equipe de Urgencia Renal vai retomar a analise em breve.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("erro", e.getMessage());
        }
        return "redirect:/solicitante/" + id;
    }

    /**
     * NOT_SUPPORTED: unica escrita relevante e {@code solicitacaoService.cancelar(...)},
     * ja {@code @Transactional} no proprio servico. {@code resolverPropria}
     * antes do try so le (posse); sem transacao de classe para envenenar, uma
     * {@code IllegalStateException} de negocio (ex.: ja triada) devolve o
     * flash de erro sem risco de {@code UnexpectedRollbackException}.
     */
    @PostMapping("/{id}/cancelar")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public String cancelar(@PathVariable Long id, Principal principal, RedirectAttributes ra) {
        Usuario usuario = resolverUsuario(principal);
        resolverPropria(id, usuario);
        try {
            Long processoCancelado = solicitacaoService.cancelar(id, usuario.getId());
            auditoria.registrar("SOLICITACAO_ONLINE_CANCELADA", "Solicitacao " + id
                + (processoCancelado != null ? " - processo " + processoCancelado + " cancelado junto" : ""));
            if (processoCancelado == null) {
                ra.addFlashAttribute("msg", "Solicitacao cancelada.");
            } else {
                // Aviso aos avaliadores DEPOIS do commit do cancelamento: falha
                // de SMTP nunca pode "descancelar" o processo (mesmo contrato do
                // convite automatico ao registrar o envio).
                List<String> naoAvisados =
                    solicitacaoService.notificarAvaliadoresCancelamento(processoCancelado);
                ra.addFlashAttribute("msg",
                    "Solicitacao cancelada. O processo foi encerrado e os avaliadores pendentes foram avisados.");
                if (!naoAvisados.isEmpty()) {
                    ra.addFlashAttribute("aviso",
                        "Nao foi possivel avisar por e-mail: " + String.join(", ", naoAvisados)
                            + ". O processo ja consta como cancelado e saiu da lista deles no portal.");
                }
            }
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("erro", e.getMessage());
        }
        return "redirect:/solicitante";
    }

    /** Sem try/catch em volta da escrita: leitura-escrita simples basta (nada a envenenar). */
    @PostMapping("/{id}/mensagem")
    @Transactional
    public String enviarMensagem(@PathVariable Long id, @RequestParam String texto,
                                 Principal principal, RedirectAttributes ra) {
        Usuario usuario = resolverUsuario(principal);
        SolicitacaoOnline s = conferirPosse(solicitacaoService.buscar(id), usuario);
        if (texto == null || texto.isBlank()) {
            ra.addFlashAttribute("erro", "A mensagem nao pode estar em branco.");
            return "redirect:/solicitante/" + id;
        }
        if (s.getStatus() == StatusSolicitacaoOnline.CANCELADA || s.getStatus() == StatusSolicitacaoOnline.PROCESSO_EXCLUIDO) {
            ra.addFlashAttribute("erro", "Nao e possivel enviar mensagem para esta solicitacao no estado atual.");
            return "redirect:/solicitante/" + id;
        }
        mensagemService.enviar(s, texto, MensagemSolicitacao.RemetenteMensagem.SOLICITANTE, usuario.getId());
        auditoria.registrar("MENSAGEM_SOLICITANTE_ENVIADA",
            "Solicitacao " + id + " - " + s.identificacao());
        return "redirect:/solicitante/" + id;
    }

    /**
     * NOT_SUPPORTED: unica escrita relevante e {@code mensagemService.apagar(...)},
     * ja {@code @Transactional} no proprio servico. Sem isso, a
     * {@code IllegalArgumentException} (mensagem de outro autor, por exemplo)
     * envenenaria a transacao de classe e o catch abaixo devolveria o flash de
     * erro as custas de um {@code UnexpectedRollbackException} no commit.
     */
    @PostMapping("/{id}/mensagem/{mensagemId}/apagar")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public String apagarMensagem(@PathVariable Long id, @PathVariable Long mensagemId,
                                  Principal principal, RedirectAttributes ra) {
        Usuario usuario = resolverUsuario(principal);
        try {
            mensagemService.apagar(mensagemId, usuario.getId(), MensagemSolicitacao.RemetenteMensagem.SOLICITANTE);
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("erro", e.getMessage());
        }
        return "redirect:/solicitante/" + id;
    }

    /**
     * Polling do chat (AJAX, chamado a cada poucos segundos pelo
     * chat-solicitacao.js) - devolve as mensagens ja projetadas pra tela e
     * marca como lidas, sem recarregar a pagina inteira.
     */
    @GetMapping("/{id}/mensagens")
    @ResponseBody
    @Transactional
    public Map<String, Object> mensagensJson(@PathVariable Long id, Principal principal) {
        Usuario usuario = resolverUsuario(principal);
        SolicitacaoOnline s = resolverPropria(id, usuario);
        mensagemService.marcarComoLidas(id, MensagemSolicitacao.RemetenteMensagem.OPERADOR, usuario.getId());
        List<MensagemSolicitacaoService.MensagemChatView> mensagens = mensagemService.paraChat(
            id, MensagemSolicitacao.RemetenteMensagem.SOLICITANTE, usuario.getId(), "Voce", "Equipe CET-RS");
        boolean podeEnviar = s.getStatus() != StatusSolicitacaoOnline.CANCELADA
            && s.getStatus() != StatusSolicitacaoOnline.PROCESSO_EXCLUIDO;
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("mensagens", mensagens);
        resp.put("podeEnviar", podeEnviar);
        return resp;
    }

    /** Sem try/catch em volta da escrita: leitura-escrita simples basta (nada a envenenar). */
    @PostMapping("/{id}/mensagem/ajax")
    @ResponseBody
    @Transactional
    public ResponseEntity<Map<String, Object>> enviarMensagemAjax(@PathVariable Long id, @RequestParam String texto,
                                                                    Principal principal) {
        Usuario usuario = resolverUsuario(principal);
        SolicitacaoOnline s = conferirPosse(solicitacaoService.buscar(id), usuario);
        if (texto == null || texto.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("erro", "A mensagem nao pode estar em branco."));
        }
        if (s.getStatus() == StatusSolicitacaoOnline.CANCELADA || s.getStatus() == StatusSolicitacaoOnline.PROCESSO_EXCLUIDO) {
            return ResponseEntity.badRequest().body(Map.of("erro",
                "Nao e possivel enviar mensagem para esta solicitacao no estado atual."));
        }
        mensagemService.enviar(s, texto, MensagemSolicitacao.RemetenteMensagem.SOLICITANTE, usuario.getId());
        auditoria.registrar("MENSAGEM_SOLICITANTE_ENVIADA",
            "Solicitacao " + id + " - " + s.identificacao());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /**
     * NOT_SUPPORTED: unica escrita relevante e {@code mensagemService.apagar(...)},
     * ja {@code @Transactional} no proprio servico. Mesmo motivo de
     * {@link #apagarMensagem}, so que devolvendo JSON/400 em vez de redirect.
     */
    @PostMapping("/{id}/mensagem/{mensagemId}/apagar/ajax")
    @ResponseBody
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ResponseEntity<Map<String, Object>> apagarMensagemAjax(@PathVariable Long id, @PathVariable Long mensagemId,
                                                                    Principal principal) {
        Usuario usuario = resolverUsuario(principal);
        try {
            mensagemService.apagar(mensagemId, usuario.getId(), MensagemSolicitacao.RemetenteMensagem.SOLICITANTE);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("erro", e.getMessage()));
        }
    }

    /**
     * Download do proprio documento anexado. Busca o anexo PELO ID persistido
     * (nunca aceita caminho vindo do request) e so serve o arquivo se ele
     * pertencer a uma solicitacao do usuario logado - mesmo padrao de posse
     * de {@link #resolverPropria}.
     */
    @GetMapping("/{id}/anexos/{anexoId}")
    @Transactional(readOnly = true)
    public ResponseEntity<Resource> baixarAnexo(@PathVariable Long id, @PathVariable Long anexoId,
                                                Principal principal) throws MalformedURLException {
        Usuario usuario = resolverUsuario(principal);
        SolicitacaoOnline s = resolverPropria(id, usuario);
        AnexoSolicitacaoOnline anexo = anexoRepo.findById(anexoId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!anexo.getSolicitacaoOnline().getId().equals(s.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Este anexo nao pertence a esta solicitacao.");
        }
        Path arquivo = anexoStorage.resolverArquivo(anexo);
        Resource resource = new UrlResource(arquivo.toUri());
        if (!resource.exists() || !resource.isReadable()) {
            return ResponseEntity.notFound().build();
        }
        String contentType = anexo.getContentType() != null
            ? anexo.getContentType() : MediaType.APPLICATION_OCTET_STREAM_VALUE;
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(contentType))
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + anexo.getNomeArquivo() + "\"")
            .body(resource);
    }

    /**
     * Download do documento final (comprovante SNT ou oficio de indeferimento)
     * do PROCESSO gerado a partir deste pedido. Whitelist explicita de
     * TipoAnexo (nunca serve qualquer anexo do processo por ID - so estes
     * dois, os unicos que a resposta final ao solicitante deve expor) +
     * checagem de posse (o processo tem que ser o gerado por ESTA solicitacao
     * do usuario logado).
     */
    @GetMapping("/{id}/processo-anexo/{anexoId}")
    @Transactional(readOnly = true)
    public ResponseEntity<Resource> baixarAnexoProcesso(@PathVariable Long id, @PathVariable Long anexoId,
                                                         Principal principal) throws MalformedURLException {
        Usuario usuario = resolverUsuario(principal);
        SolicitacaoOnline s = resolverPropria(id, usuario);
        if (s.getProcessoGerado() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        Anexo anexo;
        try {
            anexo = anexoStorageProcesso.buscar(anexoId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        boolean tipoPermitido = anexo.getTipo() == TipoAnexo.COMPROVANTE_SNT
            || anexo.getTipo() == TipoAnexo.OFICIO_INDEFERIMENTO;
        if (!tipoPermitido || !anexo.getProcesso().getId().equals(s.getProcessoGerado().getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Este anexo nao pertence a este pedido.");
        }
        Path arquivo = anexoStorageProcesso.resolverArquivo(anexo);
        Resource resource = new UrlResource(arquivo.toUri());
        if (!resource.exists() || !resource.isReadable()) {
            return ResponseEntity.notFound().build();
        }
        String contentType = anexo.getContentType() != null
            ? anexo.getContentType() : MediaType.APPLICATION_OCTET_STREAM_VALUE;
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(contentType))
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + anexo.getNomeArquivo() + "\"")
            .body(resource);
    }

    private Usuario resolverUsuario(Principal principal) {
        return usuarioRepo.findByUsername(principal.getName())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    /** Garante que a solicitacao pertence ao usuario logado (nunca ve pedido de outra equipe). */
    private SolicitacaoOnline resolverPropria(Long id, Usuario usuario) {
        return conferirPosse(solicitacaoService.buscar(id), usuario);
    }

    /** Mesma checagem de posse, para quando a solicitacao ja foi carregada. */
    private SolicitacaoOnline conferirPosse(SolicitacaoOnline s, Usuario usuario) {
        if (!s.getUsuarioSolicitante().getId().equals(usuario.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Esta solicitacao nao pertence a voce.");
        }
        return s;
    }
}
