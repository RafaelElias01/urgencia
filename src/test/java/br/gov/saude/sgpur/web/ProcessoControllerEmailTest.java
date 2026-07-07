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
import org.springframework.boot.test.mock.mockito.MockBean;
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

    @MockBean private ProcessoService processoService;
    @MockBean private ProcessoValidator processoValidator;
    @MockBean private FluxoProcessoService fluxoService;
    @MockBean private EmailTemplateService emailTemplateService;
    @MockBean private RelatorioService relatorioService;
    @MockBean private OficioService oficioService;
    @MockBean private SolicitacaoAvaliadorService solicitacaoAvaliadorService;
    @MockBean private MembroUrgenciaRenalRepository membroRepository;
    @MockBean private ParecerRepository parecerRepository;
    @MockBean private UsuarioRepository usuarioRepository;
    @MockBean private AnexoStorageService anexoStorage;
    @MockBean private AuditoriaService auditoria;
    @MockBean private DecisaoFinalService decisaoFinalService;
    @MockBean private GeminiService geminiService;
    @MockBean private EmailSenderService emailSenderService;

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
}
