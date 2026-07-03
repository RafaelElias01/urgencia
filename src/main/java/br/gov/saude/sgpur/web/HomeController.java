package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.StatusProcesso;
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
        model.addAttribute("totalProcessos", processoRepository.count());
        model.addAttribute("deferidos", processoRepository.countByStatus(StatusProcesso.DEFERIDO));
        model.addAttribute("indeferidos", processoRepository.countByStatus(StatusProcesso.INDEFERIDO));
        model.addAttribute("cancelados", processoRepository.countByStatus(StatusProcesso.CANCELADO));
        model.addAttribute("membrosAtivos", membroRepository.countByAtivoTrue());

        // Planilha do painel: todos os processos com os 3 medicos e o status
        // de cada parecer (Favoravel / Desfavoravel / Aguardando / ...).
        List<Processo> processos = processoRepository.findAllComPareceres();
        List<PainelLinha> linhas = processos.stream().map(PainelLinha::de).toList();
        model.addAttribute("linhas", linhas);

        // "Em andamento" agrupa os status nao finais (SOLICITADO, ENVIADO,
        // EM_ANALISE, SOLICITA_INFORMACAO). O que falta por processo reusa o
        // FluxoProcessoService.
        long emAndamento = 0;
        Map<Long, String> pendencias = new LinkedHashMap<>();
        for (Processo p : processos) {
            if (p.getStatus().isEmAndamento()) {
                emAndamento++;
                pendencias.put(p.getId(), fluxoService.resumoPendencia(p));
            }
        }
        model.addAttribute("emAndamento", emAndamento);
        model.addAttribute("pendencias", pendencias);

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
