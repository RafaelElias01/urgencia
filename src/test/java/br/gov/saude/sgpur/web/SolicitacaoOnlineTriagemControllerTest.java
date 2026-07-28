package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.SolicitacaoOnline;
import br.gov.saude.sgpur.domain.StatusSolicitacaoOnline;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import br.gov.saude.sgpur.service.AuditoriaService;
import br.gov.saude.sgpur.service.MensagemSolicitacaoService;
import br.gov.saude.sgpur.service.SolicitacaoOnlineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Testes do SolicitacaoOnlineTriagemController (fila de triagem do
 * operador/admin): lista, detalhe, redirecionamento de conversao e devolucao.
 * A restricao de rota por role (so ADMIN/OPERADOR, ver SecurityConfig -
 * "/processos/**" hasAnyRole ADMIN,OPERADOR) e coberta em
 * SecurityIntegrationTest (contexto completo), mesmo padrao adotado para os
 * demais controllers deste modulo - este slice cobre so a logica do
 * controller.
 */
@WebMvcTest(SolicitacaoOnlineTriagemController.class)
class SolicitacaoOnlineTriagemControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean private SolicitacaoOnlineService service;
    @MockitoBean private AuditoriaService auditoria;
    @MockitoBean private MensagemSolicitacaoService mensagemService;
    @MockitoBean private UsuarioRepository usuarioRepo;
    @MockitoBean private ParecerRepository parecerRepo;

    private SolicitacaoOnline solicitacao;

    @BeforeEach
    void setUp() {
        solicitacao = new SolicitacaoOnline();
        solicitacao.setId(50L);
        solicitacao.setPacienteNome("Fulano de Tal");
        solicitacao.setPacienteRgct("123456789-12345");
        solicitacao.setDataSituacaoEspecial(LocalDate.now());
        solicitacao.setJustificativaClinica("Quadro grave.");
        solicitacao.setStatus(StatusSolicitacaoOnline.ENVIADA);
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void listaExibeSolicitacoesPendentesDeTriagem() throws Exception {
        when(service.listarPendentesTriagem()).thenReturn(List.of(solicitacao));
        when(service.diasEspera(solicitacao)).thenReturn(new SolicitacaoOnlineService.DiasEspera(2, "bg-secondary"));

        mvc.perform(get("/processos/solicitacoes-online"))
            .andExpect(status().isOk())
            .andExpect(view().name("processos/solicitacoes-online-lista"))
            .andExpect(model().attribute("solicitacoes", List.of(solicitacao)))
            .andExpect(model().attribute("filtro", "pendentes"));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void listaComFiltroTodasExibeTodasAsSolicitacoes() throws Exception {
        when(service.listarTodas()).thenReturn(List.of(solicitacao));
        when(service.diasEspera(solicitacao)).thenReturn(new SolicitacaoOnlineService.DiasEspera(2, "bg-secondary"));

        mvc.perform(get("/processos/solicitacoes-online").param("filtro", "todas"))
            .andExpect(status().isOk())
            .andExpect(view().name("processos/solicitacoes-online-lista"))
            .andExpect(model().attribute("solicitacoes", List.of(solicitacao)))
            .andExpect(model().attribute("filtro", "todas"));

        verify(service, never()).listarPendentesTriagem();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void detalheExibeASolicitacao() throws Exception {
        when(service.buscarParaDetalhe(50L)).thenReturn(solicitacao);

        mvc.perform(get("/processos/solicitacoes-online/50"))
            .andExpect(status().isOk())
            .andExpect(view().name("processos/solicitacoes-online-detalhe"))
            .andExpect(model().attribute("solicitacao", solicitacao));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void converterRedirecionaParaNovoProcessoComOrigemPreenchida() throws Exception {
        mvc.perform(get("/processos/solicitacoes-online/50/converter"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/processos/novo?origemSolicitacaoOnlineId=50"));

        // Nao verifica "sem interacoes" puro: o GlobalModelAdvice global chama
        // service.contarPendentesTriagem() (badge da navbar) para qualquer
        // requisicao autenticada como OPERADOR/ADMIN, mesmo nesta rota. O que
        // importa aqui e que o controller em si nao chamou nenhum metodo de
        // busca/conversao da solicitacao (o redirect e so uma URL montada).
        verify(service, never()).buscar(any());
        verify(service, never()).converter(any(), any());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void devolverComSucessoRedirecionaComMensagem() throws Exception {
        when(service.buscar(50L)).thenReturn(solicitacao);
        doNothing().when(service).devolver(eq(50L), anyString());

        mvc.perform(post("/processos/solicitacoes-online/50/devolver")
                .param("observacoes", "Falta documento clinico.")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/processos/solicitacoes-online"))
            .andExpect(flash().attributeExists("msg"));

        verify(service).devolver(50L, "Falta documento clinico.");
        verify(auditoria).registrar(eq("SOLICITACAO_ONLINE_DEVOLVIDA"), any());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void devolverComErroDeNegocioRedirecionaComFlashDeErro() throws Exception {
        when(service.buscar(50L)).thenReturn(solicitacao);
        doThrow(new IllegalStateException("Esta solicitacao ja foi triada."))
            .when(service).devolver(eq(50L), anyString());

        mvc.perform(post("/processos/solicitacoes-online/50/devolver")
                .param("observacoes", "motivo")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/processos/solicitacoes-online/50"))
            .andExpect(flash().attribute("erro", "Esta solicitacao ja foi triada."));

        verify(auditoria, never()).registrar(eq("SOLICITACAO_ONLINE_DEVOLVIDA"), any());
    }
}
