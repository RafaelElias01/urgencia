package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.service.AuditoriaService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import br.gov.saude.sgpur.domain.LogAuditoria;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/auditoria")
public class AuditoriaController {

    private static final int TAMANHO = 30;

    private final AuditoriaService auditoria;

    public AuditoriaController(AuditoriaService auditoria) {
        this.auditoria = auditoria;
    }

    @GetMapping
    public String listar(@RequestParam(defaultValue = "0") int page, Model model) {
        Page<LogAuditoria> logs = auditoria.listar(PageRequest.of(Math.max(page, 0), TAMANHO));

        // Agrupa os registros da pagina por dia, preservando a ordem (mais
        // recente primeiro) que ja vem do repositorio (dataHora desc).
        Map<LocalDate, List<LogAuditoria>> gruposPorDia = new LinkedHashMap<>();
        for (LogAuditoria log : logs.getContent()) {
            LocalDate dia = log.getDataHora().toLocalDate();
            gruposPorDia.computeIfAbsent(dia, d -> new java.util.ArrayList<>()).add(log);
        }

        model.addAttribute("gruposPorDia", gruposPorDia);
        model.addAttribute("paginaAtual", logs.getNumber());
        model.addAttribute("totalPaginas", logs.getTotalPages());
        return "auditoria/lista";
    }
}
