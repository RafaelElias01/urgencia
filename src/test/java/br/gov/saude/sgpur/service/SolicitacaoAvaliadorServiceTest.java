package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.Processo;
import com.lowagie.text.Document;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfName;
import com.lowagie.text.pdf.PdfNumber;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.PdfStamper;
import com.lowagie.text.pdf.PdfWriter;
import com.lowagie.text.pdf.parser.FinalText;
import com.lowagie.text.pdf.parser.ParsedText;
import com.lowagie.text.pdf.parser.ParsedTextImpl;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.lowagie.text.pdf.parser.TextAssembler;
import com.lowagie.text.pdf.parser.Vector;
import com.lowagie.text.pdf.parser.Word;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
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

    /**
     * Contrato formal de imparcialidade: trava regressao futura (ex.: alguem
     * trocar {@code Iniciais.de(...)} por {@code p.getPacienteNome()} num
     * refactor). Usa um nome bem distintivo para que qualquer vazamento de
     * qualquer parte do nome completo (nome, meio ou sobrenome) seja
     * detectado, tanto no PDF carimbado quanto no nome do arquivo oficial.
     */
    @Test
    void contratoDeImparcialidadeNuncaExpoeNomeCompletoAosAvaliadores() throws Exception {
        Processo p = new Processo();
        p.setNumero("01/2026");
        p.setPacienteNome("Zorglubovicz Alencar Bittencourt");

        byte[] base = pdfSimples("Conteudo clinico original do documento.");
        byte[] carimbado = service.carimbarCabecalho(base, p);

        PdfReader reader = new PdfReader(carimbado);
        String textoExtraido = new PdfTextExtractor(reader).getTextFromPage(1);
        reader.close();

        assertThat(textoExtraido)
            .doesNotContain("Zorglubovicz")
            .doesNotContain("Alencar")
            .doesNotContain("Bittencourt")
            .contains("Z.A.B");

        String nomeArquivo = SolicitacaoAvaliadorService.nomeArquivoOficial(p);
        assertThat(nomeArquivo)
            .doesNotContain("Zorglubovicz")
            .doesNotContain("Alencar")
            .doesNotContain("Bittencourt")
            .contains("Z.A.B");
    }

    @Test
    void consolidarLancaExcecaoQuandoHaPdfCorrompidoNaLista() {
        byte[] valido = pdfSimples("Documento clinico valido.");
        byte[] corrompido = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10};

        assertThrows(IllegalStateException.class,
            () -> service.consolidar(List.of(valido, corrompido)));
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

    // ------------------------------------------------------------------
    // Imparcialidade: metadados do PDF (dicionario /Info + XMP)
    // ------------------------------------------------------------------

    private static final String NOME_VAZADO = "JOAO DA SILVA SANTOS";

    /**
     * Simula um laudo vindo de sistema hospitalar: nome completo do paciente
     * gravado no /Info (inclusive numa chave customizada) e no XMP.
     */
    private byte[] pdfComMetadadosDoPaciente(String texto) {
        Document doc = new Document();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfWriter writer = PdfWriter.getInstance(doc, out);
        doc.addTitle("Relatorio medico - " + NOME_VAZADO);
        doc.addAuthor(NOME_VAZADO);
        doc.addSubject("Prontuario de " + NOME_VAZADO);
        doc.addKeywords(NOME_VAZADO + ", nefrologia");
        doc.addCreator("SistemaHospitalar");
        doc.addHeader("PatientName", NOME_VAZADO);
        writer.setXmpMetadata(("<?xpacket begin=\"\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>"
            + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">"
            + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
            + "<rdf:Description rdf:about=\"\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">"
            + "<dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">" + NOME_VAZADO + "</rdf:li></rdf:Alt></dc:title>"
            + "</rdf:Description></rdf:RDF></x:xmpmeta><?xpacket end=\"w\"?>")
            .getBytes(StandardCharsets.UTF_8));
        doc.open();
        doc.add(new Paragraph(texto));
        doc.close();
        return out.toByteArray();
    }

    private void assertSemNomeNosMetadados(byte[] pdf) throws Exception {
        PdfReader reader = new PdfReader(pdf);
        Map<String, String> info = reader.getInfo();
        byte[] xmp = reader.getMetadata();
        reader.close();

        for (Map.Entry<String, String> e : info.entrySet()) {
            assertThat(e.getKey() + "=" + e.getValue())
                .as("dicionario /Info entregue ao avaliador")
                .doesNotContain("JOAO")
                .doesNotContain("SILVA")
                .doesNotContain("SANTOS")
                .doesNotContain("PatientName");
        }
        String xmpTexto = xmp == null ? "" : new String(xmp, StandardCharsets.UTF_8);
        assertThat(xmpTexto)
            .as("XMP entregue ao avaliador")
            .doesNotContain("JOAO")
            .doesNotContain("SILVA")
            .doesNotContain("SANTOS");
    }

    /**
     * Caso MAIS COMUM (um unico laudo anexado) e o que vazava: {@code
     * consolidar} devolvia o arquivo intacto e o {@link PdfStamper} preserva
     * /Info e XMP do original. O navegador exibe o {@code Title} no rotulo da
     * aba ao abrir o PDF inline em {@code /avaliador/{id}/pdf/{anexoId}} - o
     * avaliador descobriria quem e o paciente sem ninguem perceber.
     */
    @Test
    void pipelineDeUmUnicoDocumentoNaoLevaNomeDoPacienteNosMetadados() throws Exception {
        byte[] origem = pdfComMetadadosDoPaciente("Laudo clinico anonimizado no corpo.");

        // o PDF de origem realmente carrega o nome (senao o teste nao prova nada)
        PdfReader leitorOrigem = new PdfReader(origem);
        assertThat(leitorOrigem.getInfo().toString()).contains(NOME_VAZADO);
        leitorOrigem.close();

        byte[] entregue = service.carimbarCabecalho(service.consolidar(List.of(origem)), processo());

        assertSemNomeNosMetadados(entregue);
    }

    @Test
    void pipelineDeVariosDocumentosNaoLevaNomeDoPacienteNosMetadados() throws Exception {
        byte[] doc1 = pdfComMetadadosDoPaciente("Laudo A.");
        byte[] doc2 = pdfComMetadadosDoPaciente("Laudo B.");

        byte[] entregue = service.carimbarCabecalho(service.consolidar(List.of(doc1, doc2)), processo());

        assertSemNomeNosMetadados(entregue);
    }

    /**
     * Garantia no PONTO FINAL: {@code carimbarCabecalho} e o ultimo passo
     * antes do PDF virar anexo do processo, entao ele proprio precisa apagar
     * os metadados - sem depender de {@code consolidar} ter passado pelo
     * PdfCopy. Trava qualquer caminho futuro que monte o PDF de outro jeito.
     */
    @Test
    void carimbarCabecalhoApagaMetadadosMesmoSemPassarPorConsolidar() throws Exception {
        byte[] entregue = service.carimbarCabecalho(
            pdfComMetadadosDoPaciente("Laudo clinico."), processo());

        assertSemNomeNosMetadados(entregue);
    }

    /**
     * O Title que sobra e o mesmo texto seguro do carimbo (numero + iniciais),
     * que e o que o navegador mostra no rotulo da aba.
     */
    @Test
    void tituloDoPdfEntregueUsaNumeroEIniciais() throws Exception {
        byte[] entregue = service.carimbarCabecalho(
            service.consolidar(List.of(pdfComMetadadosDoPaciente("Laudo."))), processo());

        PdfReader reader = new PdfReader(entregue);
        String titulo = reader.getInfo().get("Title");
        reader.close();

        assertThat(titulo).isEqualTo("Processo CET-RS 07/2026 - Paciente M.R.M");
    }

    // ------------------------------------------------------------------
    // Carimbo em paginas com /Rotate (escaneadas em paisagem)
    // ------------------------------------------------------------------

    /** Captura a posicao (espaco do usuario) de cada trecho de texto da pagina. */
    private static final class CapturaDePosicao implements TextAssembler {
        private final List<float[]> posicoes = new ArrayList<>();
        private final List<String> textos = new ArrayList<>();

        private void registrar(ParsedTextImpl t) {
            Vector b = t.getBaseline();
            textos.add(t.getText());
            posicoes.add(new float[]{b.get(0), b.get(1)});
        }

        /** Posicao do primeiro trecho que contem {@code trecho}, ou null. */
        float[] posicaoDe(String trecho) {
            for (int i = 0; i < textos.size(); i++) {
                if (textos.get(i) != null && textos.get(i).contains(trecho)) {
                    return posicoes.get(i);
                }
            }
            return null;
        }

        @Override public void process(FinalText t, String c) { }
        @Override public void process(Word w, String c) { registrar(w); }
        @Override public void process(ParsedText t, String c) { registrar(t); }
        @Override public void renderText(FinalText t) { }
        @Override public void renderText(ParsedTextImpl t) { registrar(t); }
        @Override public FinalText endParsingContext(String c) { return new FinalText(""); }
        @Override public String getWordId() { return "w"; }
        @Override public void setPage(int p) { }
        @Override public void reset() { }
    }

    private byte[] pdfComRotacao(int rotacao) throws Exception {
        byte[] base = pdfSimples("Laudo escaneado em paisagem.");
        if (rotacao == 0) {
            return base;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfReader reader = new PdfReader(base);
        PdfStamper stamper = new PdfStamper(reader, out);
        reader.getPageN(1).put(PdfName.ROTATE, new PdfNumber(rotacao));
        stamper.close();
        reader.close();
        return out.toByteArray();
    }

    /** Converte um ponto do espaco do usuario para as coordenadas VISUAIS da pagina. */
    private float[] paraCoordenadasVisuais(PdfReader reader, int pagina, float ux, float uy) {
        Rectangle box = reader.getPageSize(pagina);
        float x0 = box.getLeft(), y0 = box.getBottom(), x1 = box.getRight(), y1 = box.getTop();
        return switch (reader.getPageRotation(pagina)) {
            case 90 -> new float[]{uy - y0, x1 - ux};
            case 180 -> new float[]{x1 - ux, y1 - uy};
            case 270 -> new float[]{y1 - uy, ux - x0};
            default -> new float[]{ux - x0, uy - y0};
        };
    }

    /**
     * Scanners gravam pagina em paisagem como retrato + {@code /Rotate 90}.
     * O espaco reservado para o carimbo precisa crescer na ALTURA VISUAL da
     * pagina (como ela e exibida) - antes crescia na largura, jogando o
     * carimbo para fora da area visivel e entregando ao avaliador paginas sem
     * a identificacao obrigatoria do processo.
     */
    @Test
    void carimboReservaEspacoNoTopoVisualMesmoComPaginaRotacionada() throws Exception {
        for (int rotacao : new int[]{0, 90, 180, 270}) {
            byte[] original = pdfComRotacao(rotacao);

            PdfReader antes = new PdfReader(original);
            Rectangle visualAntes = antes.getPageSizeWithRotation(1);
            float largOriginal = visualAntes.getWidth();
            float altOriginal = visualAntes.getHeight();
            antes.close();

            byte[] carimbado = service.carimbarCabecalho(original, processo());

            PdfReader depois = new PdfReader(carimbado);
            Rectangle visualDepois = depois.getPageSizeWithRotation(1);
            depois.close();

            assertThat(visualDepois.getWidth())
                .as("largura visual nao pode mudar (rotacao " + rotacao + ")")
                .isEqualTo(largOriginal, within(0.01f));
            assertThat(visualDepois.getHeight())
                .as("altura visual deve crescer para caber o carimbo (rotacao " + rotacao + ")")
                .isEqualTo(altOriginal + 30f, within(0.01f));
        }
    }

    /**
     * Verificacao posicional de verdade: o texto do carimbo tem de cair na
     * faixa reservada no topo VISUAL e dentro da largura visual da pagina, em
     * qualquer rotacao.
     */
    @Test
    void textoDoCarimboFicaNoTopoVisualEmQualquerRotacao() throws Exception {
        for (int rotacao : new int[]{0, 90, 180, 270}) {
            byte[] carimbado = service.carimbarCabecalho(pdfComRotacao(rotacao), processo());

            PdfReader reader = new PdfReader(carimbado);
            CapturaDePosicao captura = new CapturaDePosicao();
            new PdfTextExtractor(reader, captura).getTextFromPage(1);
            Rectangle visual = reader.getPageSizeWithRotation(1);

            float[] userSpace = captura.posicaoDe("07/2026");
            assertThat(userSpace).as("carimbo ausente na rotacao " + rotacao).isNotNull();
            float[] v = paraCoordenadasVisuais(reader, 1, userSpace[0], userSpace[1]);
            reader.close();

            assertThat(v[1])
                .as("altura visual do carimbo (rotacao " + rotacao + ")")
                .isBetween(visual.getHeight() - 30f, visual.getHeight());
            assertThat(v[0])
                .as("posicao horizontal visual do carimbo (rotacao " + rotacao + ")")
                .isBetween(0f, visual.getWidth());
            // centralizado: comeca perto do meio da largura visual
            assertThat(v[0]).isGreaterThan(visual.getWidth() / 4f);
        }
    }

    /**
     * Sub-caso do mesmo bug: o MediaBox expandido era sempre recriado com
     * origem (0,0), descartando a origem real da pagina e cortando conteudo em
     * PDFs cujo MediaBox nao comeca em zero.
     */
    @Test
    void carimboPreservaOrigemDoMediaBoxDiferenteDeZero() throws Exception {
        Document doc = new Document(new Rectangle(20, 30, 615, 872));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfWriter.getInstance(doc, out);
        doc.open();
        doc.add(new Paragraph("Laudo com MediaBox deslocado."));
        doc.close();

        byte[] carimbado = service.carimbarCabecalho(out.toByteArray(), processo());

        PdfReader reader = new PdfReader(carimbado);
        Rectangle box = reader.getPageSize(1);
        reader.close();

        assertThat(box.getLeft()).as("origem X do MediaBox").isEqualTo(20f, within(0.01f));
        assertThat(box.getBottom()).as("origem Y do MediaBox").isEqualTo(30f, within(0.01f));
        assertThat(box.getRight()).isEqualTo(615f, within(0.01f));
        assertThat(box.getTop()).as("topo expandido para o carimbo").isEqualTo(902f, within(0.01f));
    }
}
