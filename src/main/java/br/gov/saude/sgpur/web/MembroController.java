package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.domain.ResultadoParecer;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.ParecerRepository;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.LinkedHashMap;
import java.util.Map;

@Controller
@RequestMapping("/membros")
public class MembroController {

    private final MembroUrgenciaRenalRepository repo;
    private final ParecerRepository parecerRepo;

    public MembroController(MembroUrgenciaRenalRepository repo, ParecerRepository parecerRepo) {
        this.repo = repo;
        this.parecerRepo = parecerRepo;
    }

    @GetMapping
    public String listar(Model model) {
        var membros = repo.findAll();
        model.addAttribute("membros", membros);
        // Estatisticas por membro: [0]=designados, [1]=avaliados, [2]=favoraveis
        Map<Long, long[]> stats = new LinkedHashMap<>();
        for (MembroUrgenciaRenal m : membros) {
            stats.put(m.getId(), new long[]{
                parecerRepo.countByMembroId(m.getId()),
                parecerRepo.countByMembroIdAndResultadoNotNull(m.getId()),
                parecerRepo.countByMembroIdAndResultado(m.getId(), ResultadoParecer.FAVORAVEL)
            });
        }
        model.addAttribute("stats", stats);
        return "membros/lista";
    }

    @GetMapping("/novo")
    public String novo(Model model) {
        model.addAttribute("membro", new MembroUrgenciaRenal());
        return "membros/form";
    }

    @GetMapping("/{id}/editar")
    public String editar(@PathVariable Long id, Model model) {
        model.addAttribute("membro", repo.findById(id).orElseThrow());
        return "membros/form";
    }

    @PostMapping
    public String salvar(@Valid @ModelAttribute("membro") MembroUrgenciaRenal membro,
                         BindingResult result, RedirectAttributes ra) {
        if (result.hasErrors()) {
            return "membros/form";
        }
        repo.save(membro);
        ra.addFlashAttribute("msg", "Membro salvo com sucesso.");
        return "redirect:/membros";
    }

    @PostMapping("/{id}/alternar-ativo")
    public String alternarAtivo(@PathVariable Long id, RedirectAttributes ra) {
        MembroUrgenciaRenal m = repo.findById(id).orElseThrow();
        m.setAtivo(!m.isAtivo());
        repo.save(m);
        ra.addFlashAttribute("msg", "Situacao do membro atualizada.");
        return "redirect:/membros";
    }
}
