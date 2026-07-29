package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.Anexo;
import br.gov.saude.sgpur.domain.Parecer;
import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.TipoAnexo;
import com.lowagie.text.Document;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Cobre a logica de negocio extraida de
 * {@code ProcessoDecisaoController.registrarEnvio}: documento clinico PDF
 * obrigatorio, PDFs corrompidos/sem paginas ficam de fora da consolidacao
 * (aviso, nao bloqueio automatico), e o service so efetiva o envio
 * (processoService.registrarEnvio) quando ha ao menos um PDF valido.
 */
@ExtendWith(MockitoExtension.class)
class RegistroEnvioServiceTest {

    @Mock
    ProcessoService processoService;
    @Mock
    SolicitacaoAvaliadorService solicitacaoAvaliadorService;
    @Mock
    AnexoStorageService anexoStorage;
    @Mock
    AuditoriaService auditoria;
    @Mock
    EmailTemplateService emailTemplateService;
    @Mock
    EmailSenderService emailSenderService;

    RegistroEnvioService service;

    @TempDir
    Path tempDir;

    private Processo processo;

    @BeforeEach
    void setUp() {
        service = new RegistroEnvioService(processoService, solicitacaoAvaliadorService, anexoStorage,
            auditoria, emailTemplateService, emailSenderService);

        processo = new Processo();
        processo.setId(1L);
        processo.setNumero("01/2026");
        processo.setPacienteNome("Fulano de Tal");
        processo.addParecer(new Parecer(new br.gov.saude.sgpur.domain.MembroUrgenciaRenal("HCPA", "Medico", null)));

        when(processoService.buscar(1L)).thenReturn(processo);
    }

    /** Gera um PDF minimo (1 pagina) valido, para simular um documento clinico real. */
    private byte[] pdfValido() {
        try {
            Document doc = new Document(PageSize.A4);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PdfWriter.getInstance(doc, out);
            doc.open();
            doc.add(new Paragraph("Documento clinico de teste"));
            doc.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Anexo documentoClinicoPdf(String nome, byte[] bytes) throws Exception {
        Anexo a = new Anexo();
        a.setTipo(TipoAnexo.DOCUMENTO_CLINICO_AVALIADOR);
        a.setNomeArquivo(nome);
        a.setContentType("application/pdf");
        Path arquivo = tempDir.resolve(nome);
        Files.write(arquivo, bytes);
        // lenient: em cenarios onde o service bloqueia ANTES de ler o arquivo
        // (ex.: nenhum documento clinico valido), este stub nunca chega a ser
        // usado - sem lenient, o modo STRICT_STUBS padrao do MockitoExtension
        // falha com UnnecessaryStubbingException nesses testes.
        org.mockito.Mockito.lenient().when(anexoStorage.resolverArquivo(a)).thenReturn(arquivo);
        return a;
    }

    @Test
    void sucessoComUmDocumentoClinicoPdfValido() throws Exception {
        processo.addAnexo(documentoClinicoPdf("exame.pdf", pdfValido()));

        when(solicitacaoAvaliadorService.consolidar(any())).thenReturn(pdfValido());
        when(solicitacaoAvaliadorService.carimbarCabecalho(any(), eq(processo))).thenReturn(pdfValido());
        Anexo novoAnexo = new Anexo();
        novoAnexo.setId(99L);
        when(anexoStorage.salvarBytes(eq(processo), eq(TipoAnexo.SOLICITACAO_AVALIADOR),
            anyString(), anyString(), anyString(), any(byte[].class))).thenReturn(novoAnexo);
        when(processoService.registrarEnvio(1L)).thenReturn(processo);

        RegistroEnvioService.RegistroEnvioResultado resultado = service.registrar(1L);

        assertThat(resultado.ok()).isTrue();
        assertThat(resultado.mensagemErro()).isNull();
        assertThat(resultado.mensagemSucesso()).contains("Envio aos avaliadores registrado em");
        assertThat(resultado.avisos()).isEmpty();

        verify(anexoStorage).removerAntigosDoTipo(processo, TipoAnexo.SOLICITACAO_AVALIADOR, 99L);
        verify(processoService).salvar(processo);
        verify(processoService).registrarEnvio(1L);
        verify(auditoria).registrar(eq("ANEXO_ADICIONADO"), anyString());
        verify(auditoria).registrar(eq("ENVIO_AVALIADORES_REGISTRADO"), anyString());
    }

    @Test
    void pdfCorrompidoFicaDeForaComAvisoMasEnvioSeguePorHaverOutroValido() throws Exception {
        processo.addAnexo(documentoClinicoPdf("bom.pdf", pdfValido()));
        // bytes que nao formam um PDF valido - PdfReader lanca excecao ao ler
        processo.addAnexo(documentoClinicoPdf("corrompido.pdf", "isto nao e um pdf valido".getBytes()));

        when(solicitacaoAvaliadorService.consolidar(any())).thenReturn(pdfValido());
        when(solicitacaoAvaliadorService.carimbarCabecalho(any(), eq(processo))).thenReturn(pdfValido());
        Anexo novoAnexo = new Anexo();
        novoAnexo.setId(100L);
        when(anexoStorage.salvarBytes(eq(processo), eq(TipoAnexo.SOLICITACAO_AVALIADOR),
            anyString(), anyString(), anyString(), any(byte[].class))).thenReturn(novoAnexo);
        when(processoService.registrarEnvio(1L)).thenReturn(processo);

        RegistroEnvioService.RegistroEnvioResultado resultado = service.registrar(1L);

        assertThat(resultado.ok()).isTrue();
        assertThat(resultado.avisos()).anyMatch(a -> a.contains("corrompido.pdf"));
        verify(processoService).registrarEnvio(1L);
    }

    @Test
    void bloqueiaSemNenhumDocumentoClinicoPdfValido() {
        // sem nenhum documento clinico anexado

        RegistroEnvioService.RegistroEnvioResultado resultado = service.registrar(1L);

        assertThat(resultado.ok()).isFalse();
        assertThat(resultado.mensagemErro()).contains("documento clinico");
        verifyNoInteractions(solicitacaoAvaliadorService);
        verify(processoService, org.mockito.Mockito.never()).registrarEnvio(any());
    }

    /** Anexo do Portal do Solicitante ainda nao revisado (staging). */
    private Anexo documentoPortalPendente(String nome) throws Exception {
        Anexo a = new Anexo();
        a.setTipo(TipoAnexo.DOCUMENTO_PORTAL_NAO_ANONIMIZADO);
        a.setNomeArquivo(nome);
        a.setContentType("application/pdf");
        Path arquivo = tempDir.resolve(nome);
        Files.write(arquivo, pdfValido());
        org.mockito.Mockito.lenient().when(anexoStorage.resolverArquivo(a)).thenReturn(arquivo);
        return a;
    }

    /**
     * TRAVA DE ANONIMIZACAO: com SO o documento vindo do portal (staging), o
     * envio e BLOQUEADO com mensagem especifica - nao passa silenciosamente
     * nem entrega o laudo com o nome do paciente aos 3 avaliadores.
     */
    @Test
    void bloqueiaQuandoSoHaDocumentoDoPortalPendenteDeAnonimizacao() throws Exception {
        processo.addAnexo(documentoPortalPendente("laudo-original.pdf"));

        RegistroEnvioService.RegistroEnvioResultado resultado = service.registrar(1L);

        assertThat(resultado.ok()).isFalse();
        assertThat(resultado.mensagemErro())
            .contains("Confirme a anonimizacao")
            .contains("laudo-original.pdf");
        verifyNoInteractions(solicitacaoAvaliadorService);
        verify(processoService, org.mockito.Mockito.never()).registrarEnvio(any());
    }

    /**
     * O documento em staging NUNCA entra no PDF consolidado: mesmo havendo um
     * documento anonimizado valido (que libera o envio), so ele e fundido, e o
     * pendente vira aviso nao bloqueante.
     */
    @Test
    void documentoDoPortalPendenteNaoEntraNoPdfConsolidado() throws Exception {
        byte[] bytesAnonimizado = pdfValido();
        processo.addAnexo(documentoClinicoPdf("anonimizado.pdf", bytesAnonimizado));
        processo.addAnexo(documentoPortalPendente("laudo-original.pdf"));

        when(solicitacaoAvaliadorService.consolidar(any())).thenReturn(pdfValido());
        when(solicitacaoAvaliadorService.carimbarCabecalho(any(), eq(processo))).thenReturn(pdfValido());
        Anexo novoAnexo = new Anexo();
        novoAnexo.setId(101L);
        when(anexoStorage.salvarBytes(eq(processo), eq(TipoAnexo.SOLICITACAO_AVALIADOR),
            anyString(), anyString(), anyString(), any(byte[].class))).thenReturn(novoAnexo);
        when(processoService.registrarEnvio(1L)).thenReturn(processo);

        RegistroEnvioService.RegistroEnvioResultado resultado = service.registrar(1L);

        assertThat(resultado.ok()).isTrue();
        assertThat(resultado.avisos()).anyMatch(a -> a.contains("laudo-original.pdf"));
        // So o documento anonimizado foi para a consolidacao
        var captor = org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        verify(solicitacaoAvaliadorService).consolidar(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat((byte[]) captor.getValue().get(0)).isEqualTo(bytesAnonimizado);
    }

    /**
     * Processo LEGADO (convertido antes da trava): o anexo do portal foi
     * gravado como DOCUMENTO_CLINICO_AVALIADOR e continua elegivel - a
     * mudanca nao quebra processos ja existentes.
     */
    @Test
    void processoLegadoComDocumentoDoPortalNoTipoAntigoContinuaEnviando() throws Exception {
        processo.addAnexo(documentoClinicoPdf("laudo-legado.pdf", pdfValido()));

        when(solicitacaoAvaliadorService.consolidar(any())).thenReturn(pdfValido());
        when(solicitacaoAvaliadorService.carimbarCabecalho(any(), eq(processo))).thenReturn(pdfValido());
        Anexo novoAnexo = new Anexo();
        novoAnexo.setId(102L);
        when(anexoStorage.salvarBytes(eq(processo), eq(TipoAnexo.SOLICITACAO_AVALIADOR),
            anyString(), anyString(), anyString(), any(byte[].class))).thenReturn(novoAnexo);
        when(processoService.registrarEnvio(1L)).thenReturn(processo);

        RegistroEnvioService.RegistroEnvioResultado resultado = service.registrar(1L);

        assertThat(resultado.ok()).isTrue();
        assertThat(resultado.avisos()).isEmpty();
        verify(processoService).registrarEnvio(1L);
    }

    @Test
    void bloqueiaQuandoTodosOsPdfsEstaoCorrompidos() throws Exception {
        processo.addAnexo(documentoClinicoPdf("corrompido.pdf", "nao e pdf".getBytes()));

        RegistroEnvioService.RegistroEnvioResultado resultado = service.registrar(1L);

        assertThat(resultado.ok()).isFalse();
        assertThat(resultado.mensagemErro()).contains("PDF valido");
        verifyNoInteractions(solicitacaoAvaliadorService);
        verify(processoService, org.mockito.Mockito.never()).registrarEnvio(any());
    }

    // -------------------------------------------------------------------------
    // Convite automatico ao Portal do Avaliador (enviarConvitesAvaliadores).
    // Roda DEPOIS de registrar() ter commitado; nenhuma falha aqui pode derrubar
    // o envio - so vira aviso.
    // -------------------------------------------------------------------------

    /** Parecer pendente (resultado nulo) de um avaliador com o e-mail informado. */
    private Parecer parecerPendente(String nome, String email) {
        return new Parecer(new br.gov.saude.sgpur.domain.MembroUrgenciaRenal("HCPA", nome, email));
    }

    private EmailTemplate templateConvite() {
        return new EmailTemplate("convite-avaliador", "Convite", "person-check",
            "Assunto do convite", "Corpo do convite");
    }

    @Test
    void enviaConviteDoPortalParaCadaAvaliadorPendente() {
        Parecer a = parecerPendente("Dr. A", "a@hcpa.br");
        Parecer b = parecerPendente("Dr. B", "b@hcpa.br");
        when(processoService.pareceresPendentesComEmail(1L)).thenReturn(java.util.List.of(a, b));
        when(emailTemplateService.emailConviteAvaliador(eq(processo), any())).thenReturn(templateConvite());
        when(emailSenderService.enviar(anyString(), anyString(), anyString())).thenReturn(true);

        RegistroEnvioService.ConvitesResultado r = service.enviarConvitesAvaliadores(1L);

        assertThat(r.enviados()).isEqualTo(2);
        assertThat(r.avisos()).isEmpty();
        verify(emailSenderService).enviar("a@hcpa.br", "Assunto do convite", "Corpo do convite");
        verify(emailSenderService).enviar("b@hcpa.br", "Assunto do convite", "Corpo do convite");
        verify(auditoria, org.mockito.Mockito.times(2))
            .registrar(eq("CONVITE_AVALIADOR_ENVIADO"), anyString());
    }

    /**
     * Avaliador sem e-mail cadastrado nao vira erro: os demais recebem, e o nome
     * dele volta como aviso para o operador resolver e reenviar no lembrete.
     */
    @Test
    void avaliadorSemEmailViraAvisoSemImpedirOsDemais() {
        Parecer semEmail = parecerPendente("Dr. Sem Email", "   ");
        Parecer comEmail = parecerPendente("Dr. B", "b@hcpa.br");
        when(processoService.pareceresPendentesComEmail(1L))
            .thenReturn(java.util.List.of(semEmail, comEmail));
        when(emailTemplateService.emailConviteAvaliador(eq(processo), any())).thenReturn(templateConvite());
        when(emailSenderService.enviar(anyString(), anyString(), anyString())).thenReturn(true);

        RegistroEnvioService.ConvitesResultado r = service.enviarConvitesAvaliadores(1L);

        assertThat(r.enviados()).isEqualTo(1);
        assertThat(r.avisos()).containsExactly("Dr. Sem Email (sem e-mail cadastrado)");
        verify(emailSenderService).enviar("b@hcpa.br", "Assunto do convite", "Corpo do convite");
        verify(auditoria).registrar(eq("CONVITE_AVALIADOR_NAO_ENVIADO"), anyString());
    }

    /** Falha de SMTP vira aviso + auditoria de falha, nunca excecao. */
    @Test
    void falhaDeSmtpViraAvisoEAuditoriaSemLancarExcecao() {
        when(processoService.pareceresPendentesComEmail(1L))
            .thenReturn(java.util.List.of(parecerPendente("Dr. A", "a@hcpa.br")));
        when(emailTemplateService.emailConviteAvaliador(eq(processo), any())).thenReturn(templateConvite());
        when(emailSenderService.enviar(anyString(), anyString(), anyString())).thenReturn(false);

        RegistroEnvioService.ConvitesResultado r = service.enviarConvitesAvaliadores(1L);

        assertThat(r.enviados()).isZero();
        assertThat(r.avisos()).containsExactly("Dr. A (falha no envio do e-mail)");
        verify(auditoria).registrar(eq("CONVITE_AVALIADOR_FALHA"), anyString());
    }

    /**
     * Num reenvio, quem ja votou nao esta em pareceresPendentesComEmail - logo
     * nao recebe convite de novo. Sem pendentes, nenhum e-mail sai.
     */
    @Test
    void semAvaliadorPendenteNaoEnviaNenhumEmail() {
        when(processoService.pareceresPendentesComEmail(1L)).thenReturn(java.util.List.of());

        RegistroEnvioService.ConvitesResultado r = service.enviarConvitesAvaliadores(1L);

        assertThat(r.enviados()).isZero();
        assertThat(r.avisos()).isEmpty();
        verifyNoInteractions(emailSenderService, emailTemplateService);
    }
}
