package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.ControleUrgencia;
import br.gov.saude.sgpur.domain.SituacaoUrgencia;
import br.gov.saude.sgpur.service.ControleUrgenciaService;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;

@Controller
@RequestMapping("/controle-urgencias")
public class ControleUrgenciaController {

    private final ControleUrgenciaService service;

    public ControleUrgenciaController(ControleUrgenciaService service) {
        this.service = service;
    }

    @GetMapping
    public String listar(Model model) {
        model.addAttribute("registros", service.listarAtivas());
        model.addAttribute("ativas", service.contarPorSituacao(SituacaoUrgencia.ATIVA));
        model.addAttribute("renovadas", service.contarPorSituacao(SituacaoUrgencia.RENOVADA));
        model.addAttribute("expiradas", service.contarPorSituacao(SituacaoUrgencia.EXPIRADA));
        model.addAttribute("canceladas", service.contarPorSituacao(SituacaoUrgencia.CANCELADA));
        model.addAttribute("hoje", LocalDate.now());
        return "controle-urgencias/lista";
    }

    @GetMapping("/novo")
    public String novo(Model model) {
        model.addAttribute("registro", new ControleUrgencia());
        model.addAttribute("situacoes", SituacaoUrgencia.values());
        return "controle-urgencias/form";
    }

    @GetMapping("/{id}/editar")
    public String editar(@PathVariable Long id, Model model) {
        model.addAttribute("registro", service.buscarPorId(id));
        model.addAttribute("situacoes", SituacaoUrgencia.values());
        return "controle-urgencias/form";
    }

    @PostMapping
    public String salvar(@Valid @ModelAttribute("registro") ControleUrgencia registro,
                         BindingResult result, RedirectAttributes ra) {
        if (result.hasErrors()) {
            return "controle-urgencias/form";
        }
        if (registro.getId() == null) {
            service.criar(registro);
            ra.addFlashAttribute("msg", "Urgencia cadastrada com sucesso.");
        } else {
            service.atualizar(registro);
            ra.addFlashAttribute("msg", "Urgencia atualizada com sucesso.");
        }
        return "redirect:/controle-urgencias";
    }

    @PostMapping("/{id}/renovar")
    public String renovar(@PathVariable Long id, RedirectAttributes ra) {
        service.renovar(id);
        ra.addFlashAttribute("msg", "Urgencia renovada por mais " + ControleUrgenciaService.DIAS_URGENCIA + " dias.");
        return "redirect:/controle-urgencias";
    }

    @PostMapping("/{id}/cancelar")
    public String cancelar(@PathVariable Long id, RedirectAttributes ra) {
        service.cancelar(id, null);
        ra.addFlashAttribute("msg", "Urgencia cancelada.");
        return "redirect:/controle-urgencias";
    }
}
