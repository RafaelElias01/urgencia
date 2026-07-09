package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.*;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import br.gov.saude.sgpur.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Cobre os endpoints de envio real de e-mail (disparo manual): lembrete de
 * avaliacao pendente (individual e em lote) e "Enviar agora" dos textos
 * prontos. EmailSenderService e sempre mockado - nenhum teste dispara SMTP
 * real. Cobre tambem a regra de imparcialidade (nome completo do paciente
 * nunca vai no corpo do lembrete ao avaliador) e o bloqueio de envio do
 * e-mail oficial de Deferido sem o comprovante SNT anexado.
 */
@WebMvcTest(ProcessoDecisaoController.class)
class ProcessoControllerEmailTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean private ProcessoService processoService;
    @MockitoBean private ProcessoValidator processoValidator;
    @MockitoBean private FluxoProcessoService fluxoService;
    @MockitoBean private EmailTemplateService emailTemplateService;
    @MockitoBean private RelatorioService relatorioService;
    @MockitoBean private OficioService oficioService;
    @MockitoBean private SolicitacaoAvaliadorService solicitacaoAvaliadorService;
    @MockitoBean private MembroUrgenciaRenalRepository membroRepository;
    @MockitoBean private ParecerRepository parecerRepository;
    @MockitoBean private UsuarioRepository usuarioRepository;
    @MockitoBean private AnexoStorageService anexoStorage;
    @MockitoBean private AuditoriaService auditoria;
    @MockitoBean private DecisaoFinalService decisaoFinalService;
    @MockitoBean private GeminiService geminiService;
    @MockitoBean private EmailSenderService emailSenderService;

    private Processo processo;
    private MembroUrgenciaRenal membro;
    private Parecer parecerPendente;

    @BeforeEach
    void setUp() {
        membro = new MembroUrgenciaRenal("HCPA", "Dra. Veronica Horbe", "veronica@example.com");
        membro.setId(10L);

        processo = new Processo();
        processo.setId(1L);
        processo.setNumero("07/2026");
        processo.setPacienteNome("Mariana da Rosa Martins");
        processo.setStatus(StatusProcesso.ENVIADO);
        processo.setSolicitanteEmail("solicitante@example.com");

        parecerPendente = new Parecer(membro);
        parecerPendente.setId(100L);
        parecerPendente.setProcesso(processo);
        parecerPendente.setDataEnvio(LocalDate.now());
        processo.getPareceres().add(parecerPendente);

        when(processoService.buscar(1L)).thenReturn(processo);
    }

    // ===== Lembrete individual =====

    @Test
    @WithMockUser(roles = "OPERADOR")
    void lembreteAvaliadorEnviaEAuditaQuandoParecerPendente() throws Exception {
        when(parecerRepository.findById(100L)).thenReturn(Optional.of(parecerPendente));
        when(emailTemplateService.emailLembreteAvaliador(eq(processo), eq(membro)))
            .thenReturn(new EmailTemplate("lembrete-avaliador", "titulo", "bell",
                "Assunto lembrete", "Corpo do lembrete", false));
        when(emailSenderService.enviar(eq("veronica@example.com"), anyString(), anyString()))
            .thenReturn(true);

        mvc.perform(post("/processos/1/lembrete-avaliador")
                .param("parecerId", "100")
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true));

        verify(emailSenderService).enviar(eq("veronica@example.com"), eq("Assunto lembrete"), eq("Corpo do lembrete"));
        verify(auditoria).registrar(eq("LEMBRETE_AVALIADOR_ENVIADO"), anyString());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void lembreteAvaliadorFalhaQuandoParecerJaRespondido() throws Exception {
        parecerPendente.setResultado(ResultadoParecer.FAVORAVEL);
        when(parecerRepository.findById(100L)).thenReturn(Optional.of(parecerPendente));

        mvc.perform(post("/processos/1/lembrete-avaliador")
                .param("parecerId", "100")
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(false));

        verifyNoInteractions(emailSenderService);
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void lembreteAvaliadorFalhaQuandoAvaliadorSemEmail() throws Exception {
        MembroUrgenciaRenal semEmail = new MembroUrgenciaRenal("ISCMPA", "Dr. Sem Email", null);
        semEmail.setId(11L);
        Parecer parecerSemEmail = new Parecer(semEmail);
        parecerSemEmail.setId(101L);
        parecerSemEmail.setProcesso(processo);
        parecerSemEmail.setDataEnvio(LocalDate.now());
        when(parecerRepository.findById(101L)).thenReturn(Optional.of(parecerSemEmail));

        mvc.perform(post("/processos/1/lembrete-avaliador")
                .param("parecerId", "101")
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(false));

        verifyNoInteractions(emailSenderService);
    }

    /** Imparcialidade: o corpo do lembrete ao avaliador nunca contem o nome completo do paciente. */
    @Test
    @WithMockUser(roles = "OPERADOR")
    void lembreteAvaliadorNuncaExpoeNomeCompletoDoPaciente() throws Exception {
        when(parecerRepository.findById(100L)).thenReturn(Optional.of(parecerPendente));
        when(emailTemplateService.emailLembreteAvaliador(eq(processo), eq(membro)))
            .thenReturn(new EmailTemplate("lembrete-avaliador", "t", "bell",
                "Processo 07/2026 CET-RS - Paciente M.R.M.",
                "Processo 07/2026 CET-RS - Paciente M.R.M. esta disponivel para sua avaliacao.", false));
        when(emailSenderService.enviar(anyString(), anyString(), anyString())).thenReturn(true);

        mvc.perform(post("/processos/1/lembrete-avaliador")
                .param("parecerId", "100")
                .with(csrf()))
            .andExpect(status().isOk());

        var corpoCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(emailSenderService).enviar(anyString(), anyString(), corpoCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(corpoCaptor.getValue())
            .doesNotContain("Mariana da Rosa Martins")
            .contains("esta disponivel para sua avaliacao");
    }

    // ===== Lembrete em lote =====

    @Test
    @WithMockUser(roles = "OPERADOR")
    void lembretePendentesEnviaParaTodosOsPendentesComEmail() throws Exception {
        when(parecerRepository.findByProcessoIdAndResultadoIsNullAndDataEnvioIsNotNull(1L))
            .thenReturn(List.of(parecerPendente));
        when(emailTemplateService.emailLembreteAvaliador(eq(processo), eq(membro)))
            .thenReturn(new EmailTemplate("lembrete-avaliador", "t", "bell", "Assunto", "Corpo", false));
        when(emailSenderService.enviar(anyString(), anyString(), anyString())).thenReturn(true);

        mvc.perform(post("/processos/1/lembrete-pendentes").with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true));

        verify(emailSenderService, times(1)).enviar(eq("veronica@example.com"), anyString(), anyString());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void lembretePendentesFalhaQuandoNaoHaPendentes() throws Exception {
        when(parecerRepository.findByProcessoIdAndResultadoIsNullAndDataEnvioIsNotNull(1L))
            .thenReturn(List.of());

        mvc.perform(post("/processos/1/lembrete-pendentes").with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(false));

        verifyNoInteractions(emailSenderService);
    }

    // ===== Enviar e-mail pronto (accordion) =====

    @Test
    @WithMockUser(roles = "OPERADOR")
    void enviarEmailDeferidoBloqueadoSemComprovanteSnt() throws Exception {
        processo.setStatus(StatusProcesso.DEFERIDO);

        mvc.perform(post("/processos/1/email/enviar")
                .param("chave", "deferido")
                .param("assunto", "assunto")
                .param("corpo", "corpo")
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(false));

        verifyNoInteractions(emailSenderService);
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void enviarEmailDeferidoFuncionaComComprovanteSnt() throws Exception {
        processo.setStatus(StatusProcesso.DEFERIDO);
        Anexo comprovante = new Anexo();
        comprovante.setTipo(TipoAnexo.COMPROVANTE_SNT);
        processo.getAnexos().add(comprovante);
        when(emailSenderService.enviar(eq(new String[]{"solicitante@example.com"}), isNull(), anyString(), anyString()))
            .thenReturn(true);

        mvc.perform(post("/processos/1/email/enviar")
                .param("chave", "deferido")
                .param("assunto", "assunto")
                .param("corpo", "corpo")
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true));

        verify(emailSenderService).enviar(eq(new String[]{"solicitante@example.com"}), isNull(), eq("assunto"), eq("corpo"));
        verify(auditoria).registrar(eq("EMAIL_ENVIADO"), anyString());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void enviarEmailMedicosUsaEmailsDosAvaliadoresDoProcesso() throws Exception {
        when(emailSenderService.enviar(any(String[].class), any(), anyString(), anyString()))
            .thenReturn(true);

        mvc.perform(post("/processos/1/email/enviar")
                .param("chave", "medicos")
                .param("assunto", "assunto")
                .param("corpo", "corpo")
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true));

        verify(emailSenderService).enviar(eq(new String[]{"veronica@example.com"}), isNull(), eq("assunto"), eq("corpo"));
    }

    // ===== Pre-visualizacao (modal de confirmacao) =====

    /** A pre-visualizacao nunca envia e-mail; apenas devolve o conteudo a exibir. */
    @Test
    @WithMockUser(roles = "OPERADOR")
    void previewProntoDevolveDestinatariosEConteudoSemEnviar() throws Exception {
        mvc.perform(post("/processos/1/email/preview")
                .param("tipo", "pronto")
                .param("chave", "medicos")
                .param("assunto", "Assunto X")
                .param("corpo", "Corpo Y")
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.mensagens[0].destinatarios").value("veronica@example.com"))
            .andExpect(jsonPath("$.mensagens[0].assunto").value("Assunto X"))
            .andExpect(jsonPath("$.mensagens[0].corpo").value("Corpo Y"));

        verifyNoInteractions(emailSenderService);
    }

    /** O bloqueio (anexo obrigatorio ausente) ja aparece na pre-visualizacao. */
    @Test
    @WithMockUser(roles = "OPERADOR")
    void previewProntoDeferidoBloqueadoSemComprovanteSnt() throws Exception {
        processo.setStatus(StatusProcesso.DEFERIDO);

        mvc.perform(post("/processos/1/email/preview")
                .param("tipo", "pronto")
                .param("chave", "deferido")
                .param("assunto", "a")
                .param("corpo", "c")
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(false))
            .andExpect(jsonPath("$.erro").isNotEmpty());

        verifyNoInteractions(emailSenderService);
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void previewLembreteAvaliadorDevolveDestinatarioEConteudo() throws Exception {
        when(parecerRepository.findById(100L)).thenReturn(Optional.of(parecerPendente));
        when(emailTemplateService.emailLembreteAvaliador(eq(processo), eq(membro)))
            .thenReturn(new EmailTemplate("lembrete-avaliador", "t", "bell",
                "Assunto lembrete", "Corpo do lembrete", false));

        mvc.perform(post("/processos/1/email/preview")
                .param("tipo", "lembrete-avaliador")
                .param("parecerId", "100")
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.mensagens[0].destinatarios").value("veronica@example.com"))
            .andExpect(jsonPath("$.mensagens[0].assunto").value("Assunto lembrete"));

        verifyNoInteractions(emailSenderService);
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void previewLembretePendentesUmaMensagemPorAvaliador() throws Exception {
        when(parecerRepository.findByProcessoIdAndResultadoIsNullAndDataEnvioIsNotNull(1L))
            .thenReturn(List.of(parecerPendente));
        when(emailTemplateService.emailLembreteAvaliador(eq(processo), eq(membro)))
            .thenReturn(new EmailTemplate("lembrete-avaliador", "t", "bell", "Assunto", "Corpo", false));

        mvc.perform(post("/processos/1/email/preview")
                .param("tipo", "lembrete-pendentes")
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.mensagens[0].destinatarios").value("veronica@example.com"));

        verifyNoInteractions(emailSenderService);
    }

    // ===== Bloqueio de edicao em processo encerrado =====

    /** Processo encerrado bloqueia o registro de envio (redirect com flash de erro). */
    @Test
    @WithMockUser(roles = "OPERADOR")
    void registrarEnvioBloqueadoQuandoProcessoEncerrado() throws Exception {
        when(processoValidator.edicaoBloqueada(processo)).thenReturn(true);

        mvc.perform(post("/processos/1/registrar-envio").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attributeExists("erro"));

        verifyNoInteractions(anexoStorage);
        verify(processoService, never()).registrarEnvio(anyLong());
    }

    /** Processo encerrado bloqueia o lembrete a avaliador (resposta JSON de erro). */
    @Test
    @WithMockUser(roles = "OPERADOR")
    void lembreteAvaliadorBloqueadoQuandoProcessoEncerrado() throws Exception {
        when(processoValidator.edicaoBloqueada(processo)).thenReturn(true);

        mvc.perform(post("/processos/1/lembrete-avaliador")
                .param("parecerId", "100")
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(false));

        verifyNoInteractions(emailSenderService);
    }

    // ===== Bug: anexar resposta sem selecionar o parecer (resposta-avaliador) =====

    /**
     * Reproduz o bug relatado: anexar o arquivo de resposta sem escolher o
     * resultado nao pode salvar o anexo. Sem essa guarda, o anexo era salvo e
     * o parecer ficava preso sem resultado (a tela nao oferece mais como
     * completar o registro).
     */
    @Test
    @WithMockUser(roles = "OPERADOR")
    void respostaAvaliadorRejeitaSemResultadoQuandoParecerAindaPendente() throws Exception {
        when(parecerRepository.findById(100L)).thenReturn(Optional.of(parecerPendente));
        org.springframework.mock.web.MockMultipartFile arquivo =
            new org.springframework.mock.web.MockMultipartFile("arquivo", "resposta.pdf",
                "application/pdf", "conteudo".getBytes());

        mvc.perform(multipart("/processos/1/resposta-avaliador")
                .file(arquivo)
                .param("parecerId", "100")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attributeExists("erro"));

        verifyNoInteractions(anexoStorage);
    }

    /** Com o resultado selecionado junto, o anexo e o parecer sao salvos normalmente. */
    @Test
    @WithMockUser(roles = "OPERADOR")
    void respostaAvaliadorSalvaAnexoEParecerQuandoResultadoInformado() throws Exception {
        when(parecerRepository.findById(100L)).thenReturn(Optional.of(parecerPendente));
        when(processoService.atualizarStatusPorPareceres(1L)).thenReturn(processo);
        when(processoService.tentarDecisaoAutomatica(1L)).thenReturn(processo);
        org.springframework.mock.web.MockMultipartFile arquivo =
            new org.springframework.mock.web.MockMultipartFile("arquivo", "resposta.pdf",
                "application/pdf", "conteudo".getBytes());

        mvc.perform(multipart("/processos/1/resposta-avaliador")
                .file(arquivo)
                .param("parecerId", "100")
                .param("resultado", "FAVORAVEL")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("msg", org.hamcrest.Matchers.notNullValue()));

        verify(anexoStorage).salvarRespostaAvaliador(eq(processo), eq(parecerPendente), anyString(), eq(arquivo));
        org.junit.jupiter.api.Assertions.assertEquals(ResultadoParecer.FAVORAVEL, parecerPendente.getResultado());
    }

    /**
     * Recuperacao: parecer ja tem anexo mas ficou sem resultado (registro
     * anterior a essa validacao). Deve aceitar completar so o resultado, sem
     * exigir reenviar arquivo.
     */
    @Test
    @WithMockUser(roles = "OPERADOR")
    void respostaAvaliadorAceitaResultadoSemArquivoQuandoAnexoJaExiste() throws Exception {
        Anexo respostaExistente = new Anexo();
        respostaExistente.setTipo(TipoAnexo.RESPOSTA_AVALIADOR);
        respostaExistente.setParecer(parecerPendente);
        processo.getAnexos().add(respostaExistente);
        when(parecerRepository.findById(100L)).thenReturn(Optional.of(parecerPendente));
        when(processoService.atualizarStatusPorPareceres(1L)).thenReturn(processo);
        when(processoService.tentarDecisaoAutomatica(1L)).thenReturn(processo);

        mvc.perform(multipart("/processos/1/resposta-avaliador")
                .param("parecerId", "100")
                .param("resultado", "FAVORAVEL")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("msg", org.hamcrest.Matchers.notNullValue()));

        verifyNoInteractions(anexoStorage);
        org.junit.jupiter.api.Assertions.assertEquals(ResultadoParecer.FAVORAVEL, parecerPendente.getResultado());
    }
}
