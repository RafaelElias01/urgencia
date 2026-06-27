package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.StatusProcesso;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.ProcessoRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

    private final ProcessoRepository processoRepository;
    private final MembroUrgenciaRenalRepository membroRepository;

    public HomeController(ProcessoRepository processoRepository,
                          MembroUrgenciaRenalRepository membroRepository) {
        this.processoRepository = processoRepository;
        this.membroRepository = membroRepository;
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @GetMapping("/")
    public String dashboard(Model model) {
        model.addAttribute("totalProcessos", processoRepository.count());
        model.addAttribute("emAnalise", processoRepository.countByStatus(StatusProcesso.EM_ANALISE));
        model.addAttribute("deferidos", processoRepository.countByStatus(StatusProcesso.DEFERIDO));
        model.addAttribute("indeferidos", processoRepository.countByStatus(StatusProcesso.INDEFERIDO));
        model.addAttribute("cancelados", processoRepository.countByStatus(StatusProcesso.CANCELADO));
        model.addAttribute("membrosAtivos", membroRepository.countByAtivoTrue());
        model.addAttribute("ultimos",
            processoRepository.findAllByOrderByAnoDescSequencialDesc()
                .stream().limit(5).toList());
        return "dashboard";
    }
}
