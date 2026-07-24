package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.Anexo;
import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.domain.Parecer;
import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.ResultadoParecer;
import br.gov.saude.sgpur.domain.StatusProcesso;
import br.gov.saude.sgpur.domain.TipoAnexo;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Testes do RelatorioService: monta o Relatorio Final (sumario + copia dos
 * anexos PDF + pagina informativa para nao-PDF) e a Capa do Processo,
 * exercitando de quebra o PdfRelatorioBuilder e o PdfCabecalhoStamper
 * (pacote-privados, so testaveis a partir daqui). FluxoProcessoService e
 * ProcessoService sao mockados porque so a MONTAGEM do PDF importa aqui
 * (o que entra no relatorio e testado nos servicos deles mesmos).
 */
@ExtendWith(MockitoExtension.class)
class RelatorioServiceTest {

    @Mock
    private FluxoProcessoService fluxoService;
    @Mock
    private ProcessoService processoService;

    @TempDir
    Path tempDir;

    private RelatorioService novoService() {
        AnexoStorageService anexoStorage = new AnexoStorageService(null, tempDir.toString());
        return new RelatorioService(fluxoService, processoService, anexoStorage);
    }

    private Processo processoBase(StatusProcesso status) {
        Processo p = new Processo();
        p.setNumero("01/2026");
        p.setPacienteNome("Joao da Silva");
        p.setPacienteRgct("RGCT-1");
        p.setSolicitanteEquipe("Hospital X");
        p.setSolicitanteEmail("equipe@hospital.com");
        p.setDataSituacaoEspecial(LocalDate.of(2026, 1, 1));
        p.setDataCadastro(LocalDateTime.of(2026, 1, 1, 10, 0));
        p.setStatus(status);
        MembroUrgenciaRenal membro = new MembroUrgenciaRenal("HCPA", "Dr. Teste", null);
        membro.setId(1L);
        Parecer par = new Parecer(membro);
        par.setResultado(ResultadoParecer.FAVORAVEL);
        par.setDataResposta(LocalDate.of(2026, 1, 5));
        p.addParecer(par);
        return p;
    }

    @Test
    void gerarProduzPdfValidoSemAnexos() {
        Processo p = processoBase(StatusProcesso.DEFERIDO);
        when(fluxoService.montarEtapas(any())).thenReturn(List.of());
        when(processoService.contarFavoraveis(any())).thenReturn(1L);

        byte[] pdf = novoService().gerar(p);

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
    }

    @Test
    void gerarIncluiIniciaisDoPacienteNoCabecalhoENaoONomeCompleto() throws Exception {
        Processo p = processoBase(StatusProcesso.DEFERIDO);
        when(fluxoService.montarEtapas(any())).thenReturn(List.of());
        when(processoService.contarFavoraveis(any())).thenReturn(1L);

        byte[] pdf = novoService().gerar(p);

        PdfReader reader = new PdfReader(pdf);
        String pagina1 = new PdfTextExtractor(reader).getTextFromPage(1);
        reader.close();

        // O cabecalho estampado (rodape/topo institucional, repetido em toda
        // pagina) usa so as iniciais - a capa em si mostra o nome completo
        // (documento interno de arquivamento, nao enviado ao avaliador).
        assertThat(pagina1).contains("Processo CET-RS 01/2026 - Paciente J.S.");
    }

    @Test
    void gerarAdicionaPaginaDeAvisoQuandoAnexoPdfNaoExisteNoDisco() throws Exception {
        Processo p = processoBase(StatusProcesso.DEFERIDO);
        when(fluxoService.montarEtapas(any())).thenReturn(List.of());
        when(processoService.contarFavoraveis(any())).thenReturn(1L);

        Anexo anexoFantasma = new Anexo();
        anexoFantasma.setProcesso(p);
        anexoFantasma.setTipo(TipoAnexo.DOCUMENTO_PACIENTE);
        anexoFantasma.setNomeArquivo("laudo.pdf");
        anexoFantasma.setContentType("application/pdf");
        anexoFantasma.setCaminhoArmazenado("01-2026 - Joao da Silva/laudo.pdf");
        anexoFantasma.setDataUpload(LocalDateTime.of(2026, 1, 2, 9, 0));
        p.addAnexo(anexoFantasma);

        byte[] pdf = novoService().gerar(p);

        PdfReader reader = new PdfReader(pdf);
        StringBuilder texto = new StringBuilder();
        for (int i = 1; i <= reader.getNumberOfPages(); i++) {
            texto.append(new PdfTextExtractor(reader).getTextFromPage(i));
        }
        reader.close();

        assertThat(texto.toString()).contains("Anexo nao encontrado");
    }

    @Test
    void gerarMergeiaConteudoRealDeAnexoPdfExistente() throws Exception {
        Processo p = processoBase(StatusProcesso.DEFERIDO);
        when(fluxoService.montarEtapas(any())).thenReturn(List.of());
        when(processoService.contarFavoraveis(any())).thenReturn(1L);

        Path pastaProcesso = tempDir.resolve("01-2026 - Joao da Silva");
        Files.createDirectories(pastaProcesso);
        Path arquivo = pastaProcesso.resolve("laudo.pdf");
        Files.write(arquivo, pdfMinimoComTexto("MARCA-TEXTO-UNICA"));

        Anexo anexo = new Anexo();
        anexo.setProcesso(p);
        anexo.setTipo(TipoAnexo.DOCUMENTO_PACIENTE);
        anexo.setNomeArquivo("laudo.pdf");
        anexo.setContentType("application/pdf");
        anexo.setCaminhoArmazenado("01-2026 - Joao da Silva/laudo.pdf");
        anexo.setDataUpload(LocalDateTime.of(2026, 1, 2, 9, 0));
        p.addAnexo(anexo);

        byte[] pdf = novoService().gerar(p);

        PdfReader reader = new PdfReader(pdf);
        StringBuilder texto = new StringBuilder();
        for (int i = 1; i <= reader.getNumberOfPages(); i++) {
            texto.append(new PdfTextExtractor(reader).getTextFromPage(i));
        }
        reader.close();

        assertThat(texto.toString()).contains("MARCA-TEXTO-UNICA");
    }

    @Test
    void gerarAdicionaPaginaInformativaParaAnexoNaoPdf() throws Exception {
        Processo p = processoBase(StatusProcesso.DEFERIDO);
        when(fluxoService.montarEtapas(any())).thenReturn(List.of());
        when(processoService.contarFavoraveis(any())).thenReturn(1L);

        Anexo anexoImagem = new Anexo();
        anexoImagem.setProcesso(p);
        anexoImagem.setTipo(TipoAnexo.COMPROVANTE_SNT);
        anexoImagem.setNomeArquivo("comprovante.png");
        anexoImagem.setContentType("image/png");
        anexoImagem.setCaminhoArmazenado("01-2026 - Joao da Silva/comprovante.png");
        anexoImagem.setDataUpload(LocalDateTime.of(2026, 1, 3, 9, 0));
        p.addAnexo(anexoImagem);

        byte[] pdf = novoService().gerar(p);

        PdfReader reader = new PdfReader(pdf);
        StringBuilder texto = new StringBuilder();
        for (int i = 1; i <= reader.getNumberOfPages(); i++) {
            texto.append(new PdfTextExtractor(reader).getTextFromPage(i));
        }
        reader.close();

        assertThat(texto.toString())
            .contains("Anexo (formato nao-PDF)")
            .contains("comprovante.png");
    }

    @Test
    void gerarCapaProcessoProduzPdfValidoComDadosDoSolicitante() throws Exception {
        Processo p = processoBase(StatusProcesso.ENVIADO);

        byte[] pdf = novoService().gerarCapaProcesso(p);

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");

        PdfReader reader = new PdfReader(pdf);
        String texto = new PdfTextExtractor(reader).getTextFromPage(1);
        reader.close();
        assertThat(texto)
            .contains("CAPA DO PROCESSO")
            .contains("Hospital X")
            .contains("Em andamento");
    }

    /** PDF minimo valido contendo o texto informado, para simular um anexo real no disco. */
    private static byte[] pdfMinimoComTexto(String texto) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        com.lowagie.text.Document doc = new com.lowagie.text.Document();
        com.lowagie.text.pdf.PdfWriter.getInstance(doc, out);
        doc.open();
        doc.add(new com.lowagie.text.Paragraph(texto));
        doc.close();
        return out.toByteArray();
    }
}
