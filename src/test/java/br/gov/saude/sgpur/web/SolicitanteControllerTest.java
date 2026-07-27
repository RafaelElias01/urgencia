package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.Anexo;
import br.gov.saude.sgpur.domain.AnexoSolicitacaoOnline;
import br.gov.saude.sgpur.domain.Perfil;
import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.SolicitacaoOnline;
import br.gov.saude.sgpur.domain.StatusProcesso;
import br.gov.saude.sgpur.domain.StatusSolicitacaoOnline;
import br.gov.saude.sgpur.domain.TipoAnexo;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.repository.AnexoSolicitacaoOnlineRepository;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import br.gov.saude.sgpur.service.AnexoSolicitacaoOnlineStorageService;
import br.gov.saude.sgpur.service.AnexoStorageService;
import br.gov.saude.sgpur.service.AuditoriaService;
import br.gov.saude.sgpur.service.SolicitacaoOnlineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
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
    @MockitoBean private AnexoSolicitacaoOnlineRepository anexoRepo;
    @MockitoBean private AnexoSolicitacaoOnlineStorageService anexoStorage;
    @MockitoBean private AnexoStorageService anexoStorageProcesso;

    @TempDir
    Path tempDir;

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
        when(solicitacaoService.buscarParaDetalhe(50L)).thenReturn(solicitacaoDoDono);

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
        when(solicitacaoService.buscarParaDetalhe(50L)).thenReturn(solicitacaoDoDono);
        when(solicitacaoService.diasEspera(solicitacaoDoDono))
            .thenReturn(new SolicitacaoOnlineService.DiasEspera(0, "bg-secondary"));

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
    @WithMockUser(username = "solicitante1", roles = "SOLICITANTE")
    void listaExpoeResumoEDiasEsperaNoModel() throws Exception {
        when(usuarioRepo.findByUsername("solicitante1")).thenReturn(Optional.of(dono));
        when(solicitacaoService.listarMinhas(1L)).thenReturn(java.util.List.of(solicitacaoDoDono));
        when(solicitacaoService.resumir(java.util.List.of(solicitacaoDoDono)))
            .thenReturn(new br.gov.saude.sgpur.service.SolicitacaoOnlineService.Resumo(1, 1, 0, 0, 0));
        when(solicitacaoService.diasEspera(solicitacaoDoDono))
            .thenReturn(new SolicitacaoOnlineService.DiasEspera(2, "bg-secondary"));

        mvc.perform(get("/solicitante"))
            .andExpect(status().isOk())
            .andExpect(view().name("solicitante/lista"))
            .andExpect(model().attributeExists("resumo"))
            .andExpect(model().attributeExists("diasEspera"));
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
    @WithMockUser(username = "solicitante2", roles = "SOLICITANTE")
    void baixarAnexoExibe403ParaSolicitacaoDeOutroUsuario() throws Exception {
        when(usuarioRepo.findByUsername("solicitante2")).thenReturn(Optional.of(outroUsuario));
        when(solicitacaoService.buscar(50L)).thenReturn(solicitacaoDoDono);

        mvc.perform(get("/solicitante/50/anexos/999"))
            .andExpect(status().isForbidden());

        verifyNoInteractions(anexoRepo);
    }

    @Test
    @WithMockUser(username = "solicitante1", roles = "SOLICITANTE")
    void baixarAnexoRetorna403QuandoAnexoNaoPertenceAEstaSolicitacao() throws Exception {
        when(usuarioRepo.findByUsername("solicitante1")).thenReturn(Optional.of(dono));
        when(solicitacaoService.buscar(50L)).thenReturn(solicitacaoDoDono);

        SolicitacaoOnline outraSolicitacao = new SolicitacaoOnline();
        outraSolicitacao.setId(51L);
        AnexoSolicitacaoOnline anexoDeOutraSolicitacao = new AnexoSolicitacaoOnline();
        anexoDeOutraSolicitacao.setId(999L);
        anexoDeOutraSolicitacao.setSolicitacaoOnline(outraSolicitacao);
        when(anexoRepo.findById(999L)).thenReturn(Optional.of(anexoDeOutraSolicitacao));

        mvc.perform(get("/solicitante/50/anexos/999"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "solicitante1", roles = "SOLICITANTE")
    void baixarAnexoFuncionaParaAPropriaSolicitacao() throws Exception {
        when(usuarioRepo.findByUsername("solicitante1")).thenReturn(Optional.of(dono));
        when(solicitacaoService.buscar(50L)).thenReturn(solicitacaoDoDono);

        AnexoSolicitacaoOnline anexo = new AnexoSolicitacaoOnline();
        anexo.setId(999L);
        anexo.setSolicitacaoOnline(solicitacaoDoDono);
        anexo.setNomeArquivo("laudo.pdf");
        anexo.setContentType(MediaType.APPLICATION_PDF_VALUE);
        when(anexoRepo.findById(999L)).thenReturn(Optional.of(anexo));

        Path arquivo = tempDir.resolve("laudo.pdf");
        Files.write(arquivo, "conteudo".getBytes());
        when(anexoStorage.resolverArquivo(anexo)).thenReturn(arquivo);

        mvc.perform(get("/solicitante/50/anexos/999"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("laudo.pdf")));
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

    @Test
    @WithMockUser(username = "solicitante2", roles = "SOLICITANTE")
    void enviarInformacaoComplementarExibe403ParaSolicitacaoDeOutroUsuario() throws Exception {
        when(usuarioRepo.findByUsername("solicitante2")).thenReturn(Optional.of(outroUsuario));
        when(solicitacaoService.buscarParaDetalhe(50L)).thenReturn(solicitacaoDoDono);

        MockMultipartFile arquivo = new MockMultipartFile("arquivos", "resposta.pdf",
            MediaType.APPLICATION_PDF_VALUE, "conteudo".getBytes());

        mvc.perform(multipart("/solicitante/50/informacao-complementar").file(arquivo).with(csrf()))
            .andExpect(status().isForbidden());

        verify(solicitacaoService, never()).enviarInformacaoComplementar(any(), any());
    }

    @Test
    @WithMockUser(username = "solicitante1", roles = "SOLICITANTE")
    void enviarInformacaoComplementarFluxoFelizRedirecionaComMensagemDeSucesso() throws Exception {
        when(usuarioRepo.findByUsername("solicitante1")).thenReturn(Optional.of(dono));
        br.gov.saude.sgpur.domain.Processo processo = new br.gov.saude.sgpur.domain.Processo();
        processo.setId(200L);
        processo.setNumero("05/2026");
        solicitacaoDoDono.setStatus(StatusSolicitacaoOnline.CONVERTIDA);
        solicitacaoDoDono.setProcessoGerado(processo);
        when(solicitacaoService.buscarParaDetalhe(50L)).thenReturn(solicitacaoDoDono);

        MockMultipartFile arquivo = new MockMultipartFile("arquivos", "resposta.pdf",
            MediaType.APPLICATION_PDF_VALUE, "conteudo".getBytes());

        mvc.perform(multipart("/solicitante/50/informacao-complementar").file(arquivo).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/solicitante/50"))
            .andExpect(flash().attributeExists("msg"));

        verify(solicitacaoService).enviarInformacaoComplementar(eq(solicitacaoDoDono), any());
        verify(auditoria).registrar(eq("INFO_COMPLEMENTAR_RECEBIDA_PORTAL"), any());
    }

    @Test
    @WithMockUser(username = "solicitante1", roles = "SOLICITANTE")
    void enviarInformacaoComplementarComEstadoErradoVoltaComFlashDeErroSemQuebrar() throws Exception {
        when(usuarioRepo.findByUsername("solicitante1")).thenReturn(Optional.of(dono));
        br.gov.saude.sgpur.domain.Processo processo = new br.gov.saude.sgpur.domain.Processo();
        processo.setId(200L);
        processo.setNumero("05/2026");
        solicitacaoDoDono.setStatus(StatusSolicitacaoOnline.CONVERTIDA);
        solicitacaoDoDono.setProcessoGerado(processo);
        when(solicitacaoService.buscarParaDetalhe(50L)).thenReturn(solicitacaoDoDono);
        doThrow(new IllegalStateException("Este pedido nao esta aguardando informacao complementar no momento."))
            .when(solicitacaoService).enviarInformacaoComplementar(eq(solicitacaoDoDono), any());

        MockMultipartFile arquivo = new MockMultipartFile("arquivos", "resposta.pdf",
            MediaType.APPLICATION_PDF_VALUE, "conteudo".getBytes());

        mvc.perform(multipart("/solicitante/50/informacao-complementar").file(arquivo).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/solicitante/50"))
            .andExpect(flash().attributeExists("erro"));
    }

    // ----- detalhe: status do processo gerado exposto ao model -----

    @Test
    @WithMockUser(username = "solicitante1", roles = "SOLICITANTE")
    void detalheExpoeStatusDeferidoDoProcessoGerado() throws Exception {
        when(usuarioRepo.findByUsername("solicitante1")).thenReturn(Optional.of(dono));
        Processo processo = new Processo();
        processo.setId(200L);
        processo.setNumero("05/2026");
        processo.setStatus(StatusProcesso.DEFERIDO);
        solicitacaoDoDono.setStatus(StatusSolicitacaoOnline.CONVERTIDA);
        solicitacaoDoDono.setProcessoGerado(processo);
        when(solicitacaoService.buscarParaDetalhe(50L)).thenReturn(solicitacaoDoDono);
        when(solicitacaoService.diasEspera(solicitacaoDoDono))
            .thenReturn(new SolicitacaoOnlineService.DiasEspera(3, "bg-success"));
        when(anexoStorageProcesso.buscarUltimoPorTipo(200L, TipoAnexo.COMPROVANTE_SNT))
            .thenReturn(new Anexo());

        mvc.perform(get("/solicitante/50"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("statusProcesso", StatusProcesso.DEFERIDO))
            .andExpect(model().attribute("temComprovanteSnt", true));
    }

    // ----- processo-anexo: download restrito ao proprio dono, so SNT/oficio -----

    @Test
    @WithMockUser(username = "solicitante2", roles = "SOLICITANTE")
    void baixarAnexoProcessoExibe403ParaSolicitacaoDeOutroUsuario() throws Exception {
        when(usuarioRepo.findByUsername("solicitante2")).thenReturn(Optional.of(outroUsuario));
        Processo processo = new Processo();
        processo.setId(200L);
        solicitacaoDoDono.setProcessoGerado(processo);
        when(solicitacaoService.buscarParaDetalhe(50L)).thenReturn(solicitacaoDoDono);

        mvc.perform(get("/solicitante/50/processo-anexo/COMPROVANTE_SNT"))
            .andExpect(status().isForbidden());

        verifyNoInteractions(anexoStorageProcesso);
    }

    @Test
    @WithMockUser(username = "solicitante1", roles = "SOLICITANTE")
    void baixarAnexoProcessoRecusaTipoForaDaWhitelistMesmoParaODono() throws Exception {
        when(usuarioRepo.findByUsername("solicitante1")).thenReturn(Optional.of(dono));
        Processo processo = new Processo();
        processo.setId(200L);
        solicitacaoDoDono.setProcessoGerado(processo);
        when(solicitacaoService.buscarParaDetalhe(50L)).thenReturn(solicitacaoDoDono);

        // SOLICITACAO_AVALIADOR e material interno dos avaliadores - nunca pode
        // ser exposto ao solicitante, mesmo sendo dono da propria solicitacao.
        mvc.perform(get("/solicitante/50/processo-anexo/SOLICITACAO_AVALIADOR"))
            .andExpect(status().isForbidden());

        verifyNoInteractions(anexoStorageProcesso);
    }

    @Test
    @WithMockUser(username = "solicitante1", roles = "SOLICITANTE")
    void baixarAnexoProcessoFuncionaParaComprovanteSntDaPropriaSolicitacao() throws Exception {
        when(usuarioRepo.findByUsername("solicitante1")).thenReturn(Optional.of(dono));
        Processo processo = new Processo();
        processo.setId(200L);
        solicitacaoDoDono.setProcessoGerado(processo);
        when(solicitacaoService.buscarParaDetalhe(50L)).thenReturn(solicitacaoDoDono);

        Anexo anexo = new Anexo();
        anexo.setId(500L);
        anexo.setTipo(TipoAnexo.COMPROVANTE_SNT);
        anexo.setNomeArquivo("comprovante-snt.pdf");
        anexo.setContentType(MediaType.APPLICATION_PDF_VALUE);
        when(anexoStorageProcesso.buscarUltimoPorTipo(200L, TipoAnexo.COMPROVANTE_SNT)).thenReturn(anexo);

        Path arquivo = tempDir.resolve("comprovante-snt.pdf");
        Files.write(arquivo, "conteudo".getBytes());
        when(anexoStorageProcesso.resolverArquivo(anexo)).thenReturn(arquivo);

        mvc.perform(get("/solicitante/50/processo-anexo/COMPROVANTE_SNT"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Disposition",
                org.hamcrest.Matchers.containsString("comprovante-snt.pdf")));
    }

    @Test
    @WithMockUser(username = "solicitante1", roles = "SOLICITANTE")
    void baixarAnexoProcessoRetorna404QuandoTipoInvalido() throws Exception {
        when(usuarioRepo.findByUsername("solicitante1")).thenReturn(Optional.of(dono));
        Processo processo = new Processo();
        processo.setId(200L);
        solicitacaoDoDono.setProcessoGerado(processo);
        when(solicitacaoService.buscarParaDetalhe(50L)).thenReturn(solicitacaoDoDono);

        mvc.perform(get("/solicitante/50/processo-anexo/TIPO_INEXISTENTE"))
            .andExpect(status().isNotFound());
    }
}
