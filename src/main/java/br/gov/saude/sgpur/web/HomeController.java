package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.ProcessoRepository;
import br.gov.saude.sgpur.service.FluxoProcessoService;
import br.gov.saude.sgpur.service.TempoRespostaService;
import br.gov.saude.sgpur.service.TempoRespostaService.ResumoTempo;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
public class HomeController {

    private final ProcessoRepository processoRepository;
    private final MembroUrgenciaRenalRepository membroRepository;
    private final FluxoProcessoService fluxoService;
    private final TempoRespostaService tempoRespostaService;

    public HomeController(ProcessoRepository processoRepository,
                          MembroUrgenciaRenalRepository membroRepository,
                          FluxoProcessoService fluxoService,
                          TempoRespostaService tempoRespostaService) {
        this.processoRepository = processoRepository;
        this.membroRepository = membroRepository;
        this.fluxoService = fluxoService;
        this.tempoRespostaService = tempoRespostaService;
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @GetMapping("/")
    public String dashboard(Model model) {
        // Painel focado no ANO CORRENTE: contadores e planilha refletem apenas os
        // processos deste ano. Carrega uma vez (com pareceres/medicos, fetch join)
        // e agrega em Java, seguindo a convencao do projeto.
        int anoCorrente = java.time.Year.now().getValue();
        model.addAttribute("anoCorrente", anoCorrente);

        List<Processo> processos = processoRepository.findByAnoComPareceres(anoCorrente).stream()
            // Pendencias primeiro: processos EM ANDAMENTO vao para o topo; dentro de
            // cada grupo, "mais recente primeiro" (a query vem em sequencial asc).
            .sorted(java.util.Comparator
                .comparing((Processo p) -> p.getStatus().isEmAndamento() ? 0 : 1)
                .thenComparing(Processo::getSequencial,
                    java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
            .toList();

        long deferidos = 0;
        long indeferidos = 0;
        long cancelados = 0;
        long emAndamento = 0;
        Map<Long, String> pendencias = new LinkedHashMap<>();
        for (Processo p : processos) {
            switch (p.getStatus()) {
                case DEFERIDO -> deferidos++;
                case INDEFERIDO -> indeferidos++;
                case CANCELADO -> cancelados++;
                default -> { }
            }
            // "Em andamento" agrupa os status nao finais (SOLICITADO, ENVIADO,
            // EM_ANALISE, SOLICITA_INFORMACAO). O que falta por processo reusa o
            // FluxoProcessoService.
            if (p.getStatus().isEmAndamento()) {
                emAndamento++;
                pendencias.put(p.getId(), fluxoService.resumoPendencia(p));
            }
        }

        model.addAttribute("totalProcessos", processos.size());
        model.addAttribute("deferidos", deferidos);
        model.addAttribute("indeferidos", indeferidos);
        model.addAttribute("cancelados", cancelados);
        model.addAttribute("emAndamento", emAndamento);
        model.addAttribute("pendencias", pendencias);
        model.addAttribute("membrosAtivos", membroRepository.countByAtivoTrue());

        // Planilha do painel: os processos do ano com os 3 medicos e o status
        // de cada parecer (Favoravel / Desfavoravel / Aguardando / ...).
        List<PainelLinha> linhas = processos.stream().map(PainelLinha::de).toList();
        model.addAttribute("linhas", linhas);

        // Indicador: tempo de resposta medio total dos avaliadores.
        ResumoTempo tempo = tempoRespostaService.calcular();
        model.addAttribute("mediaGeralTempoTexto",
            TempoRespostaService.formatarDias(tempo.mediaGeralDias()));
        model.addAttribute("tempoDentroPrazo",
            tempo.mediaGeralDias() == null || tempo.mediaGeralDias() <= tempo.prazoDias());
        model.addAttribute("prazoDiasTempo", tempo.prazoDias());
        return "dashboard";
    }
}
