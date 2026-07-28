package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.MensagemSolicitacao;
import br.gov.saude.sgpur.domain.SolicitacaoOnline;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import br.gov.saude.sgpur.service.AuditoriaService;
import br.gov.saude.sgpur.service.MensagemSolicitacaoService;
import br.gov.saude.sgpur.service.SolicitacaoOnlineService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fila de triagem do OPERADOR/ADMIN para os pedidos enviados pelo Portal do
 * Solicitante (modulo experimental, ver docs/PLANO-SOLICITANTE.md).
 *
 * O operador revisa os dados enviados e decide: "converter" (segue para o
 * formulario normal de cadastro de processo, pre-preenchido - ver
 * ProcessoDetalheController.novo) ou "devolver" (pede correcao ao
 * solicitante). O acesso ja e restrito a ADMIN/OPERADOR pela regra
 * "/processos/**" do SecurityConfig, ja que esta rota vive sob /processos.
 */
@Controller
@RequestMapping("/processos/solicitacoes-online")
@ConditionalOnProperty(prefix = "app.solicitante", name = "habilitado", havingValue = "true", matchIfMissing = true)
@Transactional
public class SolicitacaoOnlineTriagemController {

    private final SolicitacaoOnlineService service;
    private final AuditoriaService auditoria;
    private final MensagemSolicitacaoService mensagemService;
    private final UsuarioRepository usuarioRepo;

    public SolicitacaoOnlineTriagemController(SolicitacaoOnlineService service, AuditoriaService auditoria,
            MensagemSolicitacaoService mensagemService,
            UsuarioRepository usuarioRepo) {
        this.service = service;
        this.auditoria = auditoria;
        this.mensagemService = mensagemService;
        this.usuarioRepo = usuarioRepo;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public String lista(@RequestParam(required = false, defaultValue = "pendentes") String filtro, Model model) {
        boolean todas = "todas".equals(filtro);
        List<SolicitacaoOnline> solicitacoes = todas ? service.listarTodas() : service.listarPendentesTriagem();
        Map<Long, SolicitacaoOnlineService.DiasEspera> diasEspera = new LinkedHashMap<>();
        for (SolicitacaoOnline s : solicitacoes) {
            diasEspera.put(s.getId(), service.diasEspera(s));
        }
        model.addAttribute("solicitacoes", solicitacoes);
        model.addAttribute("diasEspera", diasEspera);
        model.addAttribute("filtro", todas ? "todas" : "pendentes");
        Set<Long> idsComMsgNaoLidaSolicitante = mensagemService.idsSolicitacoesComMsgNaoLidaSolicitante();
        model.addAttribute("idsComMsgNaoLidaSolicitante", idsComMsgNaoLidaSolicitante);
        return "processos/solicitacoes-online-lista";
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public String detalhe(@PathVariable Long id, Model model) {
        model.addAttribute("solicitacao", service.buscarParaDetalhe(id));
        model.addAttribute("mensagens", mensagemService.listarPorSolicitacao(id));
        model.addAttribute("temMsgNaoLida", mensagemService.idsSolicitacoesComMsgNaoLidaSolicitante().contains(id));
        return "processos/solicitacoes-online-detalhe";
    }

    @PostMapping("/{id}/mensagem")
    public String enviarMensagem(@PathVariable Long id, @RequestParam String texto,
            Principal principal, RedirectAttributes ra) {
        SolicitacaoOnline s = service.buscar(id);
        Usuario operador = usuarioRepo.findByUsername(principal.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        if (texto == null || texto.isBlank()) {
            ra.addFlashAttribute("erro", "A mensagem nao pode estar em branco.");
            return "redirect:/processos/solicitacoes-online/" + id;
        }
        mensagemService.enviar(s, texto, MensagemSolicitacao.RemetenteMensagem.OPERADOR, operador.getId());
        auditoria.registrar("MENSAGEM_OPERADOR_ENVIADA",
                "Solicitacao " + id + " - resposta do operador " + operador.getUsername());
        ra.addFlashAttribute("msg", "Resposta enviada ao solicitante.");
        return "redirect:/processos/solicitacoes-online/" + id;
    }

    /**
     * Encaminha para o formulario normal de cadastro, pre-preenchido com os dados
     * do pedido.
     */
    @GetMapping("/{id}/converter")
    public String converter(@PathVariable Long id) {
        return "redirect:/processos/novo?origemSolicitacaoOnlineId=" + id;
    }

    @PostMapping("/{id}/devolver")
    public String devolver(@PathVariable Long id, @RequestParam String observacoes, RedirectAttributes ra) {
        SolicitacaoOnline s = service.buscar(id);
        try {
            service.devolver(id, observacoes);
            auditoria.registrar("SOLICITACAO_ONLINE_DEVOLVIDA",
                    "Solicitacao " + id + " - " + s.identificacao());
            ra.addFlashAttribute("msg", "Solicitacao devolvida para o solicitante.");
            return "redirect:/processos/solicitacoes-online";
        } catch (IllegalStateException e) {
            // Concorrencia: outro operador ja triou esta solicitacao entre a
            // abertura da tela e o submit do modal "Devolver". Volta para o
            // detalhe (nao para a lista) para nao perder o contexto/motivo
            // digitado e deixar claro o que aconteceu.
            ra.addFlashAttribute("erro", e.getMessage());
            return "redirect:/processos/solicitacoes-online/" + id;
        }
    }
}
