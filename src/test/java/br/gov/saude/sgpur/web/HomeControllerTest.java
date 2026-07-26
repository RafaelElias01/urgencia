package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.StatusProcesso;
import br.gov.saude.sgpur.service.MembroUrgenciaRenalService;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.repository.ProcessoRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import br.gov.saude.sgpur.service.FluxoProcessoService;
import br.gov.saude.sgpur.service.TempoRespostaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Year;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Testes do HomeController: /login (publico) e o Painel (/), que agrega
 * contadores do ano corrente, pendencias (FluxoProcessoService), a planilha
 * (PainelLinha) e o indicador de tempo de resposta dos avaliadores.
 */
@WebMvcTest(HomeController.class)
class HomeControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean private ProcessoRepository processoRepository;
    @MockitoBean private MembroUrgenciaRenalService membroService;
    @MockitoBean private FluxoProcessoService fluxoService;
    @MockitoBean private TempoRespostaService tempoRespostaService;
    // GlobalModelAdvice (@ControllerAdvice global) precisa dessas duas pro
    // contexto do @WebMvcTest subir - ver ArquivoControllerTest.
    @MockitoBean private UsuarioRepository usuarioRepository;
    @MockitoBean private ParecerRepository parecerRepository;

    private static Processo processo(Long id, Integer sequencial, StatusProcesso status) {
        Processo p = new Processo();
        p.setId(id);
        p.setNumero("0" + sequencial + "/2026");
        p.setSequencial(sequencial);
        p.setStatus(status);
        return p;
    }

    // GET /login nao e testavel neste slice @WebMvcTest: sem o SecurityConfig
    // real (nao carregado aqui), a seguranca autoconfigurada do Spring Boot
    // gera sua PROPRIA pagina de login padrao em /login (DefaultLoginPageGeneratingFilter),
    // que intercepta a requisicao antes dela chegar em HomeController.login() -
    // mesmo com @WithMockUser. O comportamento real (permitAll + view "login"
    // do proprio controller) ja e coberto por SecurityIntegrationTest.loginEhPublico(),
    // que roda com @SpringBootTest e o SecurityConfig completo.

    @Test
    @WithMockUser(roles = "OPERADOR")
    void painelContaOsStatusDoAnoCorrenteCorretamente() throws Exception {
        int ano = Year.now().getValue();
        List<Processo> processos = List.of(
            processo(1L, 1, StatusProcesso.DEFERIDO),
            processo(2L, 2, StatusProcesso.INDEFERIDO),
            processo(3L, 3, StatusProcesso.CANCELADO),
            processo(4L, 4, StatusProcesso.ENVIADO),
            processo(5L, 5, StatusProcesso.SOLICITADO));
        when(processoRepository.findByAnoComPareceres(ano)).thenReturn(processos);
        when(fluxoService.resumoPendencia(org.mockito.ArgumentMatchers.any())).thenReturn("Falta algo");
        when(membroService.contarAtivos()).thenReturn(3L);
        when(tempoRespostaService.calcular()).thenReturn(
            new TempoRespostaService.ResumoTempo(0, null, 0, 7, Map.of()));

        mvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(view().name("dashboard"))
            .andExpect(model().attribute("anoCorrente", ano))
            .andExpect(model().attribute("totalProcessos", 5))
            .andExpect(model().attribute("deferidos", 1L))
            .andExpect(model().attribute("indeferidos", 1L))
            .andExpect(model().attribute("cancelados", 1L))
            .andExpect(model().attribute("emAndamento", 2L))
            .andExpect(model().attribute("membrosAtivos", 3L));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void pendenciasSoSaoCalculadasParaProcessosEmAndamento() throws Exception {
        int ano = Year.now().getValue();
        Processo emAndamento = processo(1L, 1, StatusProcesso.ENVIADO);
        Processo finalizado = processo(2L, 2, StatusProcesso.DEFERIDO);
        when(processoRepository.findByAnoComPareceres(ano)).thenReturn(List.of(emAndamento, finalizado));
        when(fluxoService.resumoPendencia(emAndamento)).thenReturn("Falta a decisao");
        when(membroService.contarAtivos()).thenReturn(0L);
        when(tempoRespostaService.calcular()).thenReturn(
            new TempoRespostaService.ResumoTempo(0, null, 0, 7, Map.of()));

        mvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("pendencias", Map.of(1L, "Falta a decisao")));

        org.mockito.Mockito.verify(fluxoService, org.mockito.Mockito.never()).resumoPendencia(finalizado);
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void indicadorDeTempoDentroDoPrazoQuandoMediaMenorOuIgualAoPrazo() throws Exception {
        int ano = Year.now().getValue();
        when(processoRepository.findByAnoComPareceres(ano)).thenReturn(List.of());
        when(membroService.contarAtivos()).thenReturn(0L);
        when(tempoRespostaService.calcular()).thenReturn(
            new TempoRespostaService.ResumoTempo(10, 5.0, 1, 7, Map.of()));

        mvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("tempoDentroPrazo", true))
            .andExpect(model().attribute("mediaGeralTempoTexto", TempoRespostaService.formatarDias(5.0)))
            .andExpect(model().attribute("prazoDiasTempo", 7));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void indicadorDeTempoForaDoPrazoQuandoMediaMaiorQueOPrazo() throws Exception {
        int ano = Year.now().getValue();
        when(processoRepository.findByAnoComPareceres(ano)).thenReturn(List.of());
        when(membroService.contarAtivos()).thenReturn(0L);
        when(tempoRespostaService.calcular()).thenReturn(
            new TempoRespostaService.ResumoTempo(10, 9.0, 4, 7, Map.of()));

        mvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("tempoDentroPrazo", false));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void indicadorDeTempoConsideraDentroDoPrazoQuandoNaoHaMediaAinda() throws Exception {
        int ano = Year.now().getValue();
        when(processoRepository.findByAnoComPareceres(ano)).thenReturn(List.of());
        when(membroService.contarAtivos()).thenReturn(0L);
        when(tempoRespostaService.calcular()).thenReturn(
            new TempoRespostaService.ResumoTempo(0, null, 0, 7, Map.of()));

        mvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("tempoDentroPrazo", true))
            .andExpect(model().attribute("mediaGeralTempoTexto", "—"));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void processosEmAndamentoAparecemAntesDosFinalizadosNaPlanilha() throws Exception {
        int ano = Year.now().getValue();
        // Ordem de chegada do repositorio: finalizado primeiro, em andamento depois -
        // o controller deve reordenar (em andamento primeiro).
        Processo finalizado = processo(1L, 1, StatusProcesso.DEFERIDO);
        Processo emAndamento = processo(2L, 2, StatusProcesso.ENVIADO);
        when(processoRepository.findByAnoComPareceres(ano)).thenReturn(List.of(finalizado, emAndamento));
        when(fluxoService.resumoPendencia(emAndamento)).thenReturn("Falta algo");
        when(membroService.contarAtivos()).thenReturn(0L);
        when(tempoRespostaService.calcular()).thenReturn(
            new TempoRespostaService.ResumoTempo(0, null, 0, 7, Map.of()));

        // O controller nao expoe a lista "processos" bruta ao model - o reflexo
        // observavel da reordenacao e a planilha "linhas" (PainelLinha::de,
        // aplicado sobre a mesma lista ja reordenada).
        mvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("linhas",
                List.of(PainelLinha.de(emAndamento), PainelLinha.de(finalizado))));
    }
}
