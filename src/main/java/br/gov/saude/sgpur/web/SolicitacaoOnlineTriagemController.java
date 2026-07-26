package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.SolicitacaoOnline;
import br.gov.saude.sgpur.service.AuditoriaService;
import br.gov.saude.sgpur.service.SolicitacaoOnlineService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

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

    public SolicitacaoOnlineTriagemController(SolicitacaoOnlineService service, AuditoriaService auditoria) {
        this.service = service;
        this.auditoria = auditoria;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public String lista(Model model) {
        model.addAttribute("solicitacoes", service.listarPendentesTriagem());
        return "processos/solicitacoes-online-lista";
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public String detalhe(@PathVariable Long id, Model model) {
        model.addAttribute("solicitacao", service.buscar(id));
        return "processos/solicitacoes-online-detalhe";
    }

    /** Encaminha para o formulario normal de cadastro, pre-preenchido com os dados do pedido. */
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
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("erro", e.getMessage());
        }
        return "redirect:/processos/solicitacoes-online";
    }
}
