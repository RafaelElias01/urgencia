package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.domain.ResultadoParecer;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.service.TempoRespostaService;
import br.gov.saude.sgpur.service.TempoRespostaService.ResumoTempo;
import br.gov.saude.sgpur.service.TempoRespostaService.TempoMembro;
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
    private final TempoRespostaService tempoRespostaService;

    public MembroController(MembroUrgenciaRenalRepository repo, ParecerRepository parecerRepo,
                            TempoRespostaService tempoRespostaService) {
        this.repo = repo;
        this.parecerRepo = parecerRepo;
        this.tempoRespostaService = tempoRespostaService;
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

        // Tempo de resposta: texto pronto por membro (mantem a view limpa) +
        // flags de "fora do prazo" para o destaque visual.
        ResumoTempo tempo = tempoRespostaService.calcular();
        Map<Long, String> tempoTexto = new LinkedHashMap<>();
        Map<Long, Boolean> tempoForaPrazo = new LinkedHashMap<>();
        for (var e : tempo.porMembro().entrySet()) {
            TempoMembro tm = e.getValue();
            tempoTexto.put(e.getKey(), TempoRespostaService.formatarDias(tm.mediaDias()));
            tempoForaPrazo.put(e.getKey(),
                tm.mediaDias() != null && tm.mediaDias() > tempo.prazoDias());
        }
        model.addAttribute("tempoTexto", tempoTexto);
        model.addAttribute("tempoForaPrazo", tempoForaPrazo);
        model.addAttribute("mediaGeralTexto", TempoRespostaService.formatarDias(tempo.mediaGeralDias()));
        model.addAttribute("totalAvaliadosTempo", tempo.totalAvaliados());
        model.addAttribute("foraDoPrazoTempo", tempo.foraDoPrazo());
        model.addAttribute("prazoDias", tempo.prazoDias());
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
        // So pode haver um coordenador CET-RS por vez (as regras de decisao por
        // maioria assumem no maximo um). Ao marcar este membro como coordenador,
        // desmarca automaticamente qualquer outro que ja estivesse marcado.
        if (membro.isCoordenador()) {
            repo.findByCoordenadorTrue().stream()
                .filter(outro -> !outro.getId().equals(membro.getId()))
                .forEach(outro -> {
                    outro.setCoordenador(false);
                    repo.save(outro);
                });
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
