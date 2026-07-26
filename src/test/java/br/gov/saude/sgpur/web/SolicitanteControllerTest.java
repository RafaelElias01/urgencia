package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.Perfil;
import br.gov.saude.sgpur.domain.SolicitacaoOnline;
import br.gov.saude.sgpur.domain.StatusSolicitacaoOnline;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import br.gov.saude.sgpur.service.AuditoriaService;
import br.gov.saude.sgpur.service.SolicitacaoOnlineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Testes do SolicitanteController: posse da propria solicitacao (nunca
 * acessa/cancela pedido de outro usuario) e o fluxo feliz de criacao com
 * anexo. Restricao de ROLE_SOLICITANTE por rota (SecurityConfig) e coberta em
 * SecurityIntegrationTest, seguindo o mesmo padrao do AvaliadorControllerTest
 * (testes de role/matcher ficam no teste de integracao com contexto completo;
 * este slice cobre so a logica do controller).
 */
@WebMvcTest(SolicitanteController.class)
class SolicitanteControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean private UsuarioRepository usuarioRepo;
    @MockitoBean private SolicitacaoOnlineService solicitacaoService;
    @MockitoBean private AuditoriaService auditoria;
    @MockitoBean private ParecerRepository parecerRepository;

    private Usuario dono;
    private Usuario outroUsuario;
    private SolicitacaoOnline solicitacaoDoDono;

    @BeforeEach
    void setUp() {
        dono = new Usuario();
        dono.setId(1L);
        dono.setUsername("solicitante1");
        dono.setPerfil(Perfil.SOLICITANTE);
        dono.setEquipeSolicitante("HCPA");
        dono.setEmail("hcpa@example.com");

        outroUsuario = new Usuario();
        outroUsuario.setId(2L);
        outroUsuario.setUsername("solicitante2");
        outroUsuario.setPerfil(Perfil.SOLICITANTE);
        outroUsuario.setEquipeSolicitante("HNSC");
        outroUsuario.setEmail("hnsc@example.com");

        solicitacaoDoDono = new SolicitacaoOnline();
        solicitacaoDoDono.setId(50L);
        solicitacaoDoDono.setUsuarioSolicitante(dono);
        solicitacaoDoDono.setPacienteNome("Fulano de Tal");
        solicitacaoDoDono.setPacienteRgct("123456789-12345");
        solicitacaoDoDono.setDataSituacaoEspecial(LocalDate.now());
        solicitacaoDoDono.setJustificativaClinica("Quadro grave.");
        solicitacaoDoDono.setStatus(StatusSolicitacaoOnline.ENVIADA);
    }

    @Test
    @WithMockUser(username = "solicitante2", roles = "SOLICITANTE")
    void detalheExibe403ParaSolicitacaoDeOutroUsuario() throws Exception {
        when(usuarioRepo.findByUsername("solicitante2")).thenReturn(Optional.of(outroUsuario));
        when(solicitacaoService.buscar(50L)).thenReturn(solicitacaoDoDono);

        mvc.perform(get("/solicitante/50"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "solicitante2", roles = "SOLICITANTE")
    void cancelarExibe403ParaSolicitacaoDeOutroUsuario() throws Exception {
        when(usuarioRepo.findByUsername("solicitante2")).thenReturn(Optional.of(outroUsuario));
        when(solicitacaoService.buscar(50L)).thenReturn(solicitacaoDoDono);

        mvc.perform(post("/solicitante/50/cancelar").with(csrf()))
            .andExpect(status().isForbidden());

        // 403 antes de qualquer tentativa de cancelamento no service
        verify(solicitacaoService, never()).cancelar(any(), any());
    }

    @Test
    @WithMockUser(username = "solicitante1", roles = "SOLICITANTE")
    void detalheExibeAPropriaSolicitacaoNormalmente() throws Exception {
        when(usuarioRepo.findByUsername("solicitante1")).thenReturn(Optional.of(dono));
        when(solicitacaoService.buscar(50L)).thenReturn(solicitacaoDoDono);

        mvc.perform(get("/solicitante/50"))
            .andExpect(status().isOk())
            .andExpect(view().name("solicitante/detalhe"));
    }

    @Test
    @WithMockUser(username = "solicitante1", roles = "SOLICITANTE")
    void cancelarAPropriaSolicitacaoFunciona() throws Exception {
        when(usuarioRepo.findByUsername("solicitante1")).thenReturn(Optional.of(dono));
        when(solicitacaoService.buscar(50L)).thenReturn(solicitacaoDoDono);
        doNothing().when(solicitacaoService).cancelar(50L, 1L);

        mvc.perform(post("/solicitante/50/cancelar").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/solicitante"));

        verify(solicitacaoService).cancelar(50L, 1L);
        verify(auditoria).registrar(eq("SOLICITACAO_ONLINE_CANCELADA"), any());
    }

    @Test
    @WithMockUser(username = "usuarioSemCadastro", roles = "SOLICITANTE")
    void resolverUsuarioLanca401QuandoUsuarioAutenticadoNaoExisteNoBanco() throws Exception {
        when(usuarioRepo.findByUsername("usuarioSemCadastro")).thenReturn(Optional.empty());

        mvc.perform(get("/solicitante"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "solicitante1", roles = "SOLICITANTE")
    void criarComAnexoFluxoFelizRedirecionaComMensagemDeSucesso() throws Exception {
        when(usuarioRepo.findByUsername("solicitante1")).thenReturn(Optional.of(dono));
        SolicitacaoOnline salva = new SolicitacaoOnline();
        salva.setId(60L);
        salva.setPacienteNome("Ciclano da Silva");
        when(solicitacaoService.criar(any(SolicitacaoOnline.class), eq(dono), any()))
            .thenReturn(salva);

        MockMultipartFile documento = new MockMultipartFile("documentos", "laudo.pdf",
            MediaType.APPLICATION_PDF_VALUE, "conteudo".getBytes());

        mvc.perform(multipart("/solicitante/nova")
                .file(documento)
                .param("pacienteNome", "Ciclano da Silva")
                .param("pacienteRgct", "987654321-12345")
                .param("dataSituacaoEspecial", LocalDate.now().toString())
                .param("justificativaClinica", "Quadro clinico grave, necessita avaliacao urgente.")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/solicitante"));

        verify(solicitacaoService).criar(any(SolicitacaoOnline.class), eq(dono), any());
        verify(auditoria).registrar(eq("SOLICITACAO_ONLINE_ENVIADA"), any());
    }

    @Test
    @WithMockUser(username = "solicitante1", roles = "SOLICITANTE")
    void criarComErroDeNegocioVoltaParaOFormularioComMensagemDeErro() throws Exception {
        when(usuarioRepo.findByUsername("solicitante1")).thenReturn(Optional.of(dono));
        when(solicitacaoService.criar(any(SolicitacaoOnline.class), eq(dono), any()))
            .thenThrow(new IllegalStateException("Usuario solicitante sem equipe vinculada."));

        mvc.perform(multipart("/solicitante/nova")
                .param("pacienteNome", "Ciclano da Silva")
                .param("pacienteRgct", "987654321-12345")
                .param("dataSituacaoEspecial", LocalDate.now().toString())
                .param("justificativaClinica", "Quadro clinico grave.")
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(view().name("solicitante/nova"))
            .andExpect(model().attributeExists("erro"));
    }
}
