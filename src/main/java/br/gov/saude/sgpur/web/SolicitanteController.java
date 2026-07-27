package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.Anexo;
import br.gov.saude.sgpur.domain.AnexoSolicitacaoOnline;
import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.SolicitacaoOnline;
import br.gov.saude.sgpur.domain.StatusSolicitacaoOnline;
import br.gov.saude.sgpur.domain.TipoAnexo;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.repository.AnexoSolicitacaoOnlineRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import br.gov.saude.sgpur.service.AnexoSolicitacaoOnlineStorageService;
import br.gov.saude.sgpur.service.AnexoStorageService;
import br.gov.saude.sgpur.service.AuditoriaService;
import br.gov.saude.sgpur.service.SolicitacaoOnlineService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
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
 */
@Controller
@RequestMapping("/solicitante")
@ConditionalOnProperty(prefix = "app.solicitante", name = "habilitado", havingValue = "true", matchIfMissing = true)
@Transactional
public class SolicitanteController {

    private final UsuarioRepository usuarioRepo;
    private final SolicitacaoOnlineService solicitacaoService;
    private final AuditoriaService auditoria;
    private final AnexoSolicitacaoOnlineRepository anexoRepo;
    private final AnexoSolicitacaoOnlineStorageService anexoStorage;
    private final AnexoStorageService anexoStorageProcesso;

    public SolicitanteController(UsuarioRepository usuarioRepo,
                                 SolicitacaoOnlineService solicitacaoService,
                                 AuditoriaService auditoria,
                                 AnexoSolicitacaoOnlineRepository anexoRepo,
                                 AnexoSolicitacaoOnlineStorageService anexoStorage,
                                 AnexoStorageService anexoStorageProcesso) {
        this.usuarioRepo = usuarioRepo;
        this.solicitacaoService = solicitacaoService;
        this.auditoria = auditoria;
        this.anexoRepo = anexoRepo;
        this.anexoStorage = anexoStorage;
        this.anexoStorageProcesso = anexoStorageProcesso;
    }

    /**
     * Anexos do PROCESSO que o solicitante pode baixar assim que a decisao
     * sai — whitelist restrita ao comprovante SNT (Deferido) e ao oficio de
     * indeferimento (Indeferido). NUNCA inclui material dos avaliadores
     * (SOLICITACAO_AVALIADOR, RESPOSTA_AVALIADOR, DOCUMENTO_CLINICO_AVALIADOR
     * etc.) — expor isso quebraria a regra de imparcialidade do sistema (a
     * deliberacao interna dos avaliadores nunca e visivel ao solicitante).
     */
    private static final java.util.Set<TipoAnexo> ANEXOS_PROCESSO_PERMITIDOS_AO_SOLICITANTE =
        java.util.Set.of(TipoAnexo.COMPROVANTE_SNT, TipoAnexo.OFICIO_INDEFERIMENTO);

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
        // Dias de espera so faz sentido para quem ainda aguarda triagem (ENVIADA);
        // reaproveita o mesmo calculo/formatacao ja usado na fila do operador.
        Map<Long, SolicitacaoOnlineService.DiasEspera> diasEspera = new LinkedHashMap<>();
        // Se true, o processo gerado esta pausado aguardando informacao complementar
        // do solicitante - a lista destaca com um badge de "Acao necessaria".
        Map<Long, Boolean> acaoNecessaria = new LinkedHashMap<>();
        for (SolicitacaoOnline s : minhas) {
            if (s.getStatus() == StatusSolicitacaoOnline.ENVIADA) {
                diasEspera.put(s.getId(), solicitacaoService.diasEspera(s));
            }
            acaoNecessaria.put(s.getId(), solicitacaoService.precisaInformacaoComplementar(s));
        }
        model.addAttribute("diasEspera", diasEspera);
        model.addAttribute("acaoNecessaria", acaoNecessaria);
        model.addAttribute("equipe", usuario.getEquipeSolicitante());
        return "solicitante/lista";
    }

    @GetMapping("/nova")
    public String nova(Principal principal, Model model) {
        Usuario usuario = resolverUsuario(principal);
        SolicitacaoOnline s = new SolicitacaoOnline();
        s.setDataSituacaoEspecial(LocalDate.now());
        model.addAttribute("solicitacao", s);
        model.addAttribute("equipe", usuario.getEquipeSolicitante());
        model.addAttribute("email", usuario.getEmail());
        return "solicitante/nova";
    }

    @PostMapping("/nova")
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
    @Transactional(readOnly = true)
    public String detalhe(@PathVariable Long id, Principal principal, Model model) {
        Usuario usuario = resolverUsuario(principal);
        // buscarParaDetalhe (e nao buscar) porque o template mostra anexos e o
        // processo gerado, ambos LAZY - com open-in-view: false o Thymeleaf
        // roda fora da transacao e um proxy nao inicializado vira 500.
        SolicitacaoOnline s = conferirPosse(solicitacaoService.buscarParaDetalhe(id), usuario);
        model.addAttribute("solicitacao", s);
        // Tempo decorrido desde o envio, sempre (independente do status) - contexto
        // util pro solicitante entender ha quanto tempo o pedido esta parado/foi resolvido.
        model.addAttribute("diasEspera", solicitacaoService.diasEspera(s));
        model.addAttribute("precisaInformacaoComplementar", solicitacaoService.precisaInformacaoComplementar(s));
        // Status do PROCESSO gerado (nao da SolicitacaoOnline) - permite a tela
        // mostrar o desfecho real (Deferido/Indeferido/Solicita informacao) em
        // vez do texto generico anterior. Anexos do processo sao LAZY e nao vem
        // no fetch join de buscarParaDetalhe, entao a existencia do comprovante
        // SNT/oficio e checada aqui dentro da transacao (nunca no template).
        Processo processoGerado = s.getProcessoGerado();
        if (processoGerado != null) {
            model.addAttribute("statusProcesso", processoGerado.getStatus());
            model.addAttribute("temComprovanteSnt",
                anexoStorageProcesso.buscarUltimoPorTipo(processoGerado.getId(), TipoAnexo.COMPROVANTE_SNT) != null);
            model.addAttribute("temOficioIndeferimento",
                anexoStorageProcesso.buscarUltimoPorTipo(processoGerado.getId(), TipoAnexo.OFICIO_INDEFERIMENTO) != null);
        }
        return "solicitante/detalhe";
    }

    /**
     * Download restrito de um anexo do PROCESSO (nao da SolicitacaoOnline)
     * vinculado a esta solicitacao — usado pelo solicitante para baixar o
     * comprovante de insercao no SNT (Deferido) ou o oficio de indeferimento
     * (Indeferido). Whitelist dupla de seguranca: (1) so os dois tipos em
     * {@link #ANEXOS_PROCESSO_PERMITIDOS_AO_SOLICITANTE} sao aceitos, mesmo
     * que o dono peca outro tipo; (2) a solicitacao precisa pertencer ao
     * usuario logado (mesmo padrao de posse de {@link #baixarAnexo}). Nunca
     * expoe material dos avaliadores (imparcialidade).
     */
    @GetMapping("/{id}/processo-anexo/{tipo}")
    @Transactional(readOnly = true)
    public ResponseEntity<Resource> baixarAnexoProcesso(@PathVariable Long id, @PathVariable String tipo,
                                                         Principal principal) throws MalformedURLException {
        Usuario usuario = resolverUsuario(principal);
        SolicitacaoOnline s = conferirPosse(solicitacaoService.buscarParaDetalhe(id), usuario);
        Processo processo = s.getProcessoGerado();
        if (processo == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        TipoAnexo tipoAnexo;
        try {
            tipoAnexo = TipoAnexo.valueOf(tipo);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (!ANEXOS_PROCESSO_PERMITIDOS_AO_SOLICITANTE.contains(tipoAnexo)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Este tipo de anexo nao pode ser baixado pelo solicitante.");
        }
        Anexo anexo = anexoStorageProcesso.buscarUltimoPorTipo(processo.getId(), tipoAnexo);
        if (anexo == null) {
            return ResponseEntity.notFound().build();
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

    /**
     * Upload direto, pelo solicitante, dos documentos/dados pedidos por um
     * avaliador quando o processo esta pausado (SOLICITA_INFORMACAO). O
     * solicitante SO ENVIA - quem decide retomar a analise continua sendo o
     * OPERADOR (POST /processos/{id}/retomar-analise), nunca este endpoint.
     */
    @PostMapping("/{id}/informacao-complementar")
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

    @PostMapping("/{id}/cancelar")
    public String cancelar(@PathVariable Long id, Principal principal, RedirectAttributes ra) {
        Usuario usuario = resolverUsuario(principal);
        resolverPropria(id, usuario);
        try {
            solicitacaoService.cancelar(id, usuario.getId());
            auditoria.registrar("SOLICITACAO_ONLINE_CANCELADA", "Solicitacao " + id);
            ra.addFlashAttribute("msg", "Solicitacao cancelada.");
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("erro", e.getMessage());
        }
        return "redirect:/solicitante";
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
