package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.ControleUrgencia;
import br.gov.saude.sgpur.domain.SituacaoUrgencia;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import br.gov.saude.sgpur.service.ControleUrgenciaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Testes do ControleUrgenciaController: listagem com contadores por
 * situacao, form de novo/editar, criacao vs atualizacao (via presenca do
 * id), e as acoes de renovar/cancelar.
 */
@WebMvcTest(ControleUrgenciaController.class)
class ControleUrgenciaControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean private ControleUrgenciaService service;
    // GlobalModelAdvice (@ControllerAdvice global) precisa dessas duas pro
    // contexto do @WebMvcTest subir - ver ArquivoControllerTest.
    @MockitoBean private UsuarioRepository usuarioRepository;
    @MockitoBean private ParecerRepository parecerRepository;

    @Test
    @WithMockUser(roles = "OPERADOR")
    void listaExpoeRegistrosEContadoresPorSituacao() throws Exception {
        ControleUrgencia c = new ControleUrgencia("Maria", "RGCT1", "Equipe A", "O+", SituacaoUrgencia.ATIVA, null);
        when(service.listarAtivas()).thenReturn(java.util.List.of(c));
        when(service.contarPorSituacao(SituacaoUrgencia.ATIVA)).thenReturn(3L);
        when(service.contarPorSituacao(SituacaoUrgencia.RENOVADA)).thenReturn(2L);
        when(service.contarPorSituacao(SituacaoUrgencia.EXPIRADA)).thenReturn(1L);
        when(service.contarPorSituacao(SituacaoUrgencia.CANCELADA)).thenReturn(0L);

        mvc.perform(get("/controle-urgencias"))
            .andExpect(status().isOk())
            .andExpect(view().name("controle-urgencias/lista"))
            .andExpect(model().attribute("registros", java.util.List.of(c)))
            .andExpect(model().attribute("ativas", 3L))
            .andExpect(model().attribute("renovadas", 2L))
            .andExpect(model().attribute("expiradas", 1L))
            .andExpect(model().attribute("canceladas", 0L));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void novoExibeFormularioVazioComAsSituacoesDisponiveis() throws Exception {
        mvc.perform(get("/controle-urgencias/novo"))
            .andExpect(status().isOk())
            .andExpect(view().name("controle-urgencias/form"))
            .andExpect(model().attribute("situacoes", SituacaoUrgencia.values()));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void editarCarregaORegistroExistente() throws Exception {
        ControleUrgencia c = new ControleUrgencia("Maria", "RGCT1", "Equipe A", "O+", SituacaoUrgencia.ATIVA, null);
        c.setId(10L);
        when(service.buscarPorId(10L)).thenReturn(c);

        mvc.perform(get("/controle-urgencias/10/editar"))
            .andExpect(status().isOk())
            .andExpect(view().name("controle-urgencias/form"))
            .andExpect(model().attribute("registro", c));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void salvarSemIdChamaCriar() throws Exception {
        mvc.perform(post("/controle-urgencias")
                .param("nomePaciente", "Maria Silva")
                .param("rgct", "RGCT1")
                .param("equipe", "Equipe A")
                .param("abo", "O+")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/controle-urgencias"))
            .andExpect(flash().attribute("msg", org.hamcrest.Matchers.containsString("cadastrada")));

        verify(service).criar(any());
        verify(service, never()).atualizar(any());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void salvarComIdChamaAtualizar() throws Exception {
        mvc.perform(post("/controle-urgencias")
                .param("id", "10")
                .param("nomePaciente", "Maria Silva")
                .param("rgct", "RGCT1")
                .param("equipe", "Equipe A")
                .param("abo", "O+")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/controle-urgencias"))
            .andExpect(flash().attribute("msg", org.hamcrest.Matchers.containsString("atualizada")));

        verify(service).atualizar(any());
        verify(service, never()).criar(any());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void salvarComErroDeBindingVoltaParaOFormularioSemChamarOServico() throws Exception {
        // dataVencimento com formato invalido - erro de conversao de tipo do
        // proprio WebDataBinder, populando BindingResult independente de
        // anotacoes de bean validation na entidade.
        mvc.perform(post("/controle-urgencias")
                .param("nomePaciente", "Maria Silva")
                .param("dataVencimento", "isto-nao-e-uma-data")
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(view().name("controle-urgencias/form"));

        verify(service, never()).criar(any());
        verify(service, never()).atualizar(any());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void renovarChamaServicoERedireciona() throws Exception {
        mvc.perform(post("/controle-urgencias/10/renovar").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/controle-urgencias"))
            .andExpect(flash().attribute("msg", org.hamcrest.Matchers.containsString(
                String.valueOf(ControleUrgenciaService.DIAS_URGENCIA))));

        verify(service).renovar(10L);
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void cancelarChamaServicoSemObservacoesERedireciona() throws Exception {
        mvc.perform(post("/controle-urgencias/10/cancelar").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/controle-urgencias"))
            .andExpect(flash().attribute("msg", "Urgencia cancelada."));

        verify(service).cancelar(eq(10L), eq((String) null));
    }
}
