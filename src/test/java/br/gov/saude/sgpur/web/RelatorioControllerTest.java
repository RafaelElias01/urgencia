package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.repository.ProcessoRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import br.gov.saude.sgpur.service.MembroUrgenciaRenalService;
import br.gov.saude.sgpur.service.RelatorioAnualService;
import br.gov.saude.sgpur.service.RelatorioAvaliadorService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Testes do RelatorioController: seletor + PDF do Relatorio Geral por ano, e
 * seletor + PDF do Relatorio Individual do Avaliador (404 se o membro nao
 * existe).
 */
@WebMvcTest(RelatorioController.class)
class RelatorioControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean private ProcessoRepository processoRepository;
    @MockitoBean private MembroUrgenciaRenalService membroService;
    @MockitoBean private RelatorioAnualService relatorioAnualService;
    @MockitoBean private RelatorioAvaliadorService relatorioAvaliadorService;
    // GlobalModelAdvice (@ControllerAdvice global) precisa dessas duas pro
    // contexto do @WebMvcTest subir - ver ArquivoControllerTest.
    @MockitoBean private UsuarioRepository usuarioRepository;
    @MockitoBean private ParecerRepository parecerRepository;

    @Test
    @WithMockUser(roles = "OPERADOR")
    void anualExpoeApenasOsAnosComProcessos() throws Exception {
        when(processoRepository.findAnosComProcessos()).thenReturn(List.of(2025, 2026));

        mvc.perform(get("/relatorios/anual"))
            .andExpect(status().isOk())
            .andExpect(view().name("relatorios/anual"))
            .andExpect(model().attribute("anos", List.of(2025, 2026)));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void anualPdfGeraOPdfDoAnoInformado() throws Exception {
        Processo p = new Processo();
        p.setId(1L);
        p.setNumero("01/2026");
        when(processoRepository.findByAnoComPareceres(2026)).thenReturn(List.of(p));
        when(relatorioAnualService.gerar(2026, List.of(p))).thenReturn("pdf-fake".getBytes());

        mvc.perform(get("/relatorios/anual/2026/pdf"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PDF))
            .andExpect(header().string("Content-Disposition",
                org.hamcrest.Matchers.containsString("relatorio-2026.pdf")))
            .andExpect(content().bytes("pdf-fake".getBytes()));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void avaliadorExpoeAnosEMembrosAtivos() throws Exception {
        MembroUrgenciaRenal membro = new MembroUrgenciaRenal("HCPA", "Dra. Ana", "ana@hcpa.edu.br");
        membro.setId(10L);
        when(processoRepository.findAnosComProcessos()).thenReturn(List.of(2026));
        when(membroService.listarAtivos()).thenReturn(List.of(membro));

        mvc.perform(get("/relatorios/avaliador"))
            .andExpect(status().isOk())
            .andExpect(view().name("relatorios/avaliador"))
            .andExpect(model().attribute("anos", List.of(2026)))
            .andExpect(model().attribute("membros", List.of(membro)));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void avaliadorPdfGeraOPdfDoMembroEAno() throws Exception {
        MembroUrgenciaRenal membro = new MembroUrgenciaRenal("HCPA", "Dra. Ana", "ana@hcpa.edu.br");
        membro.setId(10L);
        when(membroService.buscar(10L)).thenReturn(membro);
        when(processoRepository.findByAnoComPareceres(2026)).thenReturn(List.of());
        when(relatorioAvaliadorService.gerar(2026, membro, List.of())).thenReturn("pdf-avaliador".getBytes());

        mvc.perform(get("/relatorios/avaliador/2026/10/pdf"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PDF))
            .andExpect(header().string("Content-Disposition",
                org.hamcrest.Matchers.containsString("relatorio-avaliador-2026-10.pdf")))
            .andExpect(content().bytes("pdf-avaliador".getBytes()));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void avaliadorPdfRetorna404QuandoMembroNaoExiste() throws Exception {
        when(membroService.buscar(99L)).thenThrow(new IllegalArgumentException("Membro nao encontrado: 99"));

        mvc.perform(get("/relatorios/avaliador/2026/99/pdf"))
            .andExpect(status().isNotFound());
    }
}
