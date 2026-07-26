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
 * {@code ProcessoDecisaoController.registrarEnvio}: comprovante de envio e
 * documento clinico PDF obrigatorios, PDFs corrompidos/sem paginas ficam de
 * fora da consolidacao (aviso, nao bloqueio automatico), e o service so
 * efetiva o envio (processoService.registrarEnvio) quando ha ao menos um PDF
 * valido.
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

    RegistroEnvioService service;

    @TempDir
    Path tempDir;

    private Processo processo;

    @BeforeEach
    void setUp() {
        service = new RegistroEnvioService(processoService, solicitacaoAvaliadorService, anexoStorage, auditoria);

        processo = new Processo();
        processo.setId(1L);
        processo.setNumero("01/2026");
        processo.setPacienteNome("Fulano de Tal");
        processo.getPareceres().add(new Parecer(new br.gov.saude.sgpur.domain.MembroUrgenciaRenal("HCPA", "Medico", null)));

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

    private Anexo comprovanteEnvio() {
        Anexo a = new Anexo();
        a.setTipo(TipoAnexo.EMAIL_ENVIADO_AVALIADORES);
        a.setNomeArquivo("comprovante.pdf");
        return a;
    }

    private Anexo documentoClinicoPdf(String nome, byte[] bytes) throws Exception {
        Anexo a = new Anexo();
        a.setTipo(TipoAnexo.DOCUMENTO_CLINICO_AVALIADOR);
        a.setNomeArquivo(nome);
        a.setContentType("application/pdf");
        Path arquivo = tempDir.resolve(nome);
        Files.write(arquivo, bytes);
        // lenient: em cenarios onde o service bloqueia ANTES de ler o arquivo
        // (ex.: sem comprovante de envio), este stub nunca chega a ser usado -
        // sem lenient, o modo STRICT_STUBS padrao do MockitoExtension falha
        // com UnnecessaryStubbingException nesses testes.
        org.mockito.Mockito.lenient().when(anexoStorage.resolverArquivo(a)).thenReturn(arquivo);
        return a;
    }

    @Test
    void sucessoComUmDocumentoClinicoPdfValidoEComprovante() throws Exception {
        processo.getAnexos().add(comprovanteEnvio());
        processo.getAnexos().add(documentoClinicoPdf("exame.pdf", pdfValido()));

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

        verify(anexoStorage).removerAntigosDoTipo(1L, TipoAnexo.SOLICITACAO_AVALIADOR, 99L);
        verify(processoService).salvar(processo);
        verify(processoService).registrarEnvio(1L);
        verify(auditoria).registrar(eq("ANEXO_ADICIONADO"), anyString());
        verify(auditoria).registrar(eq("ENVIO_AVALIADORES_REGISTRADO"), anyString());
    }

    @Test
    void pdfCorrompidoFicaDeForaComAvisoMasEnvioSeguePorHaverOutroValido() throws Exception {
        processo.getAnexos().add(comprovanteEnvio());
        processo.getAnexos().add(documentoClinicoPdf("bom.pdf", pdfValido()));
        // bytes que nao formam um PDF valido - PdfReader lanca excecao ao ler
        processo.getAnexos().add(documentoClinicoPdf("corrompido.pdf", "isto nao e um pdf valido".getBytes()));

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
        processo.getAnexos().add(comprovanteEnvio());
        // sem nenhum documento clinico anexado

        RegistroEnvioService.RegistroEnvioResultado resultado = service.registrar(1L);

        assertThat(resultado.ok()).isFalse();
        assertThat(resultado.mensagemErro()).contains("documento clinico");
        verifyNoInteractions(solicitacaoAvaliadorService);
        verify(processoService, org.mockito.Mockito.never()).registrarEnvio(any());
    }

    @Test
    void bloqueiaSemComprovanteDeEnvio() throws Exception {
        // sem TipoAnexo.EMAIL_ENVIADO_AVALIADORES anexado, mesmo com documento clinico valido
        processo.getAnexos().add(documentoClinicoPdf("exame.pdf", pdfValido()));

        RegistroEnvioService.RegistroEnvioResultado resultado = service.registrar(1L);

        assertThat(resultado.ok()).isFalse();
        assertThat(resultado.mensagemErro()).contains("comprovante de envio");
        verifyNoInteractions(solicitacaoAvaliadorService);
        verify(processoService, org.mockito.Mockito.never()).registrarEnvio(any());
    }

    @Test
    void bloqueiaQuandoTodosOsPdfsEstaoCorrompidos() throws Exception {
        processo.getAnexos().add(comprovanteEnvio());
        processo.getAnexos().add(documentoClinicoPdf("corrompido.pdf", "nao e pdf".getBytes()));

        RegistroEnvioService.RegistroEnvioResultado resultado = service.registrar(1L);

        assertThat(resultado.ok()).isFalse();
        assertThat(resultado.mensagemErro()).contains("PDF valido");
        verifyNoInteractions(solicitacaoAvaliadorService);
        verify(processoService, org.mockito.Mockito.never()).registrarEnvio(any());
    }
}
