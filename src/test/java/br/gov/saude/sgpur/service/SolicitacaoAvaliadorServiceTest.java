package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.Processo;
import com.lowagie.text.Document;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.PdfWriter;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifica o novo comportamento da Etapa 2 (Envio): o PDF dos avaliadores e
 * montado a partir dos documentos clinicos e recebe, em cada pagina, um
 * cabecalho carimbado com numero do processo + iniciais do paciente (sem o
 * nome completo). A folha-rosto gerada pelo sistema deixou de entrar no fluxo.
 */
class SolicitacaoAvaliadorServiceTest {

    private final SolicitacaoAvaliadorService service = new SolicitacaoAvaliadorService();

    private Processo processo() {
        Processo p = new Processo();
        p.setNumero("07/2026");
        p.setPacienteNome("Mariana da Rosa Martins");
        return p;
    }

    private byte[] pdfSimples(String texto) {
        Document doc = new Document();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfWriter.getInstance(doc, out);
        doc.open();
        doc.add(new Paragraph(texto));
        doc.close();
        return out.toByteArray();
    }

    @Test
    void nomeArquivoOficialUsaIniciaisENaoNomeCompleto() {
        String nome = SolicitacaoAvaliadorService.nomeArquivoOficial(processo());
        assertEquals("Processo CET-RS 07-2026 - Paciente M.R.M.pdf", nome);
        assertFalse(nome.contains("Mariana"));
    }

    @Test
    void carimbarCabecalhoAdicionaCabecalhoComIniciaisEPreservaConteudo() throws Exception {
        byte[] base = pdfSimples("Conteudo clinico original do documento.");
        byte[] carimbado = service.carimbarCabecalho(base, processo());

        PdfReader reader = new PdfReader(carimbado);
        assertEquals(1, reader.getNumberOfPages());
        String texto = new PdfTextExtractor(reader).getTextFromPage(1);
        reader.close();

        // conteudo original intacto
        assertTrue(texto.contains("Conteudo clinico original"), "conteudo original deve permanecer");
        // cabecalho institucional + numero/iniciais
        assertTrue(texto.contains("URGENCIA RENAL"), "linha institucional ausente");
        assertTrue(texto.contains("07/2026"), "numero do processo ausente no cabecalho");
        assertTrue(texto.contains("M.R.M"), "iniciais do paciente ausentes no cabecalho");
        // NUNCA o nome completo
        assertFalse(texto.contains("Mariana"), "nome completo NAO pode aparecer aos avaliadores");
    }

    @Test
    void consolidarECarimbarAplicaCabecalhoEmTodasAsPaginas() throws Exception {
        byte[] doc1 = pdfSimples("Documento clinico A.");
        byte[] doc2 = pdfSimples("Documento clinico B.");
        byte[] consolidado = service.consolidar(List.of(doc1, doc2));
        byte[] carimbado = service.carimbarCabecalho(consolidado, processo());

        PdfReader reader = new PdfReader(carimbado);
        assertEquals(2, reader.getNumberOfPages());
        PdfTextExtractor extractor = new PdfTextExtractor(reader);
        for (int i = 1; i <= 2; i++) {
            String texto = extractor.getTextFromPage(i);
            assertTrue(texto.contains("Processo CET-RS 07/2026"),
                "cabecalho ausente na pagina " + i);
            assertTrue(texto.contains("M.R.M"), "iniciais ausentes na pagina " + i);
        }
        reader.close();
    }
}
