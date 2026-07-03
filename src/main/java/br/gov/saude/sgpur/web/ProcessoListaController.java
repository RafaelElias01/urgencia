package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.StatusProcesso;
import br.gov.saude.sgpur.service.FluxoProcessoService;
import br.gov.saude.sgpur.service.ProcessoService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Listagem e filtros da home de /processos. */
@Controller
@RequestMapping("/processos")
public class ProcessoListaController {

    private final ProcessoService processoService;
    private final FluxoProcessoService fluxoService;

    public ProcessoListaController(ProcessoService processoService,
                                   FluxoProcessoService fluxoService) {
        this.processoService = processoService;
        this.fluxoService = fluxoService;
    }

    @ModelAttribute("statusValores")
    public StatusProcesso[] statusValores() {
        return StatusProcesso.values();
    }

    @GetMapping
    public String listar(@RequestParam(required = false) String q,
                         @RequestParam(required = false) StatusProcesso status,
                         @RequestParam(defaultValue = "0") int page,
                         Model model) {
        var pagina = processoService.buscar(q, status,
            org.springframework.data.domain.PageRequest.of(Math.max(page, 0), 15));
        var processos = pagina.getContent();
        model.addAttribute("processos", processos);
        model.addAttribute("paginaAtual", pagina.getNumber());
        model.addAttribute("totalPaginas", pagina.getTotalPages());
        model.addAttribute("q", q);
        model.addAttribute("statusSelecionado", status);
        // resumo de pendencia por processo (id -> texto)
        java.util.Map<Long, String> pendencias = new java.util.LinkedHashMap<>();
        for (Processo p : processos) {
            pendencias.put(p.getId(), fluxoService.resumoPendencia(p));
        }
        model.addAttribute("pendencias", pendencias);
        return "processos/lista";
    }
}
