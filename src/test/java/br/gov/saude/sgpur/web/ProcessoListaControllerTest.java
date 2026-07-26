package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.StatusProcesso;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import br.gov.saude.sgpur.service.FluxoProcessoService;
import br.gov.saude.sgpur.service.ProcessoService;
import br.gov.saude.sgpur.service.SolicitacaoOnlineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Testes do ProcessoListaController: listagem paginada de /processos, com
 * filtros de busca/status e o resumo de pendencia por processo (reusa
 * FluxoProcessoService, a mesma regra do checklist do detalhe).
 */
@WebMvcTest(ProcessoListaController.class)
class ProcessoListaControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean private ProcessoService processoService;
    @MockitoBean private FluxoProcessoService fluxoService;
    // GlobalModelAdvice (@ControllerAdvice global) precisa dessas duas pro
    // contexto do @WebMvcTest subir - ver ArquivoControllerTest.
    @MockitoBean private UsuarioRepository usuarioRepository;
    @MockitoBean private ParecerRepository parecerRepository;
    @MockitoBean private SolicitacaoOnlineService solicitacaoOnlineService;

    private Processo processo;

    @BeforeEach
    void setUp() {
        processo = new Processo();
        processo.setId(1L);
        processo.setNumero("01/2026");
        processo.setStatus(StatusProcesso.ENVIADO);
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void listaSemFiltrosUsaPaginaZeroETamanho15() throws Exception {
        Page<Processo> pagina = new PageImpl<>(List.of(processo), PageRequest.of(0, 15), 1);
        when(processoService.buscar(isNull(), isNull(), eq(PageRequest.of(0, 15)))).thenReturn(pagina);
        when(fluxoService.resumoPendencia(processo)).thenReturn("Falta o Envio");

        mvc.perform(get("/processos"))
            .andExpect(status().isOk())
            .andExpect(view().name("processos/lista"))
            .andExpect(model().attribute("processos", List.of(processo)))
            .andExpect(model().attribute("paginaAtual", 0))
            .andExpect(model().attribute("totalPaginas", 1))
            .andExpect(model().attribute("pendencias", org.hamcrest.Matchers.hasEntry(1L, "Falta o Envio")));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void filtraPorTermoDeBuscaEStatus() throws Exception {
        Page<Processo> pagina = new PageImpl<>(List.of(processo), PageRequest.of(0, 15), 1);
        when(processoService.buscar(eq("Maria"), eq(StatusProcesso.ENVIADO), any())).thenReturn(pagina);
        when(fluxoService.resumoPendencia(any())).thenReturn("");

        mvc.perform(get("/processos").param("q", "Maria").param("status", "ENVIADO"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("q", "Maria"))
            .andExpect(model().attribute("statusSelecionado", StatusProcesso.ENVIADO));

        verify(processoService).buscar("Maria", StatusProcesso.ENVIADO, PageRequest.of(0, 15));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void paginaNegativaEhTratadaComoZero() throws Exception {
        Page<Processo> paginaVazia = new PageImpl<>(List.of(), PageRequest.of(0, 15), 0);
        when(processoService.buscar(isNull(), isNull(), eq(PageRequest.of(0, 15)))).thenReturn(paginaVazia);

        mvc.perform(get("/processos").param("page", "-5"))
            .andExpect(status().isOk());

        verify(processoService).buscar(null, null, PageRequest.of(0, 15));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void expoeTodosOsValoresDeStatusParaOFiltro() throws Exception {
        Page<Processo> pagina = new PageImpl<>(List.of(), PageRequest.of(0, 15), 0);
        when(processoService.buscar(isNull(), isNull(), any())).thenReturn(pagina);

        mvc.perform(get("/processos"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("statusValores", StatusProcesso.values()));
    }
}
