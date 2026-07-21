package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.StatusProcesso;
import br.gov.saude.sgpur.repository.ProcessoRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Locale;

/**
 * Arquivo: listagem, apenas leitura, dos processos ENCERRADOS
 * (Deferido/Indeferido/Cancelado). Um "local" separado para consultar e
 * visualizar processos antigos, sem se misturar ao fluxo ativo em /processos.
 * A reabertura de um encerrado e acao exclusiva do ADMIN (ver
 * ProcessoDetalheController.reabrir / SecurityConfig).
 */
@Controller
@RequestMapping("/arquivo")
public class ArquivoController {

    private static final List<StatusProcesso> ENCERRADOS =
        List.of(StatusProcesso.DEFERIDO, StatusProcesso.INDEFERIDO, StatusProcesso.CANCELADO);

    private final ProcessoRepository processoRepository;

    public ArquivoController(ProcessoRepository processoRepository) {
        this.processoRepository = processoRepository;
    }

    @GetMapping
    public String listar(@RequestParam(required = false) String q, Model model) {
        List<Processo> encerrados = processoRepository
            .findByStatusInOrderByAnoDescSequencialDesc(ENCERRADOS);

        // Busca simples em Java (mesmo criterio da /processos: paciente, numero,
        // equipe solicitante), sobre o conjunto ja pequeno dos encerrados.
        if (q != null && !q.isBlank()) {
            String termo = q.toLowerCase(Locale.ROOT).trim();
            encerrados = encerrados.stream()
                .filter(p -> contem(p.getPacienteNome(), termo)
                    || contem(p.getNumero(), termo)
                    || contem(p.getSolicitanteEquipe(), termo))
                .toList();
        }

        model.addAttribute("processos", encerrados);
        model.addAttribute("q", q);
        return "arquivo/lista";
    }

    private static boolean contem(String valor, String termo) {
        return valor != null && valor.toLowerCase(Locale.ROOT).contains(termo);
    }
}
