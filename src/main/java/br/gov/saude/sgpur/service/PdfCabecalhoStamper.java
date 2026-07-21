package br.gov.saude.sgpur.service;

import com.lowagie.text.Element;
import com.lowagie.text.Image;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfDictionary;
import com.lowagie.text.pdf.PdfName;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.PdfRectangle;
import com.lowagie.text.pdf.PdfStamper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.io.ByteArrayOutputStream;

/**
 * Padrao unico de cabecalho institucional para os documentos PDF oficiais do
 * sistema (Relatorio Final e Relatorio Anual): logo do RS + 2 linhas de texto
 * centralizadas + linha separadora + numeracao "Pagina X de Y", estampados em
 * TODAS as paginas do documento (inclusive a capa) via {@link PdfStamper}.
 *
 * <p>Aumenta o MediaBox de cada pagina no topo (em vez de sobrepor o conteudo
 * existente), entao o conteudo original fica sempre abaixo do cabecalho, sem
 * ser cortado - inclusive em paginas de anexos com tamanho diferente de A4.
 */
final class PdfCabecalhoStamper {

    /**
     * Nome institucional padrao, usado em TODOS os documentos oficiais
     * (Oficio, Relatorio Final, Relatorio Anual, carimbo do avaliador) - uma
     * unica fonte de verdade para evitar o que ja aconteceu uma vez: um
     * documento ficar com o nome do orgao desatualizado enquanto os outros
     * ja tinham sido corrigidos.
     */
    static final String NOME_INSTITUICAO = "Central de Transplantes do Estado do Rio Grande do Sul";

    /** Linha da secretaria, usada nas capas dos relatorios (Final e Anual). */
    static final String SECRETARIA = "SECRETARIA DE SAUDE";

    private static final Logger log = LoggerFactory.getLogger(PdfCabecalhoStamper.class);

    private static final float MARGEM_ESQ = 40;
    private static final float MARGEM_DIR = 40;
    private static final float LOGO_TAMANHO = 33;
    private static final float LOGO_MARGEM = 36;

    /** Altura (pt) reservada no topo para o cabecalho completo (logo + 2 linhas + numeracao). */
    static final float ALTURA_CABECALHO = 55;

    /** Logo do RS em cache (carregado uma vez e reusado em todos os documentos). */
    private static Image LOGO_CACHE;

    private static Image carregarLogo() {
        if (LOGO_CACHE != null) return LOGO_CACHE;
        try {
            byte[] logoBytes = PdfCabecalhoStamper.class.getClassLoader()
                .getResourceAsStream("static/brasao.png").readAllBytes();
            LOGO_CACHE = Image.getInstance(logoBytes);
        } catch (Exception e) {
            log.warn("Logo nao encontrado em static/brasao.png, cabecalho sem imagem");
        }
        return LOGO_CACHE;
    }

    private PdfCabecalhoStamper() {
    }

    /**
     * Expande o MediaBox/CropBox de UMA pagina de {@code reader} em
     * {@code alturaExtra} pontos no TOPO, deslocando o conteudo original para
     * baixo (em vez de desenhar por cima dele). Retorna a nova coordenada Y do
     * topo da pagina, para quem for desenhar no over-content saber onde
     * posicionar o cabecalho.
     *
     * <p>Compartilhado por quem precisa carimbar um cabecalho sem arriscar
     * cobrir o conteudo original de paginas com pouca ou nenhuma margem
     * superior (ex.: documentos clinicos escaneados) - usado tanto por
     * {@link #estampar} quanto por
     * {@link SolicitacaoAvaliadorService#carimbarCabecalho}.
     */
    static float expandirTopo(PdfReader reader, int pagina, float alturaExtra) {
        Rectangle pageSize = reader.getPageSize(pagina);
        float largura = pageSize.getWidth();
        float novaAltura = pageSize.getHeight() + alturaExtra;

        PdfDictionary pageDict = reader.getPageN(pagina);
        PdfRectangle novoMediaBox = new PdfRectangle(0, 0, largura, novaAltura);
        pageDict.put(PdfName.MEDIABOX, novoMediaBox);
        pageDict.put(PdfName.CROPBOX, novoMediaBox);

        return novaAltura;
    }

    /**
     * Estampa o cabecalho institucional padrao em todas as paginas de
     * {@code pdf}.
     *
     * @param pdf    PDF de entrada (bytes)
     * @param linha1 primeira linha do cabecalho (institucional, em negrito)
     * @param linha2 segunda linha do cabecalho (identificacao do documento)
     */
    static byte[] estampar(byte[] pdf, String linha1, String linha2) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            PdfReader reader = new PdfReader(pdf);
            PdfStamper stamper = new PdfStamper(reader, baos);

            Image logo = carregarLogo();

            BaseFont bf = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
            int totalPaginas = reader.getNumberOfPages();

            for (int i = 1; i <= totalPaginas; i++) {
                Rectangle pageSize = reader.getPageSize(i);
                float largura = pageSize.getWidth();
                float topo = expandirTopo(reader, i, ALTURA_CABECALHO);
                float largUtil = largura - MARGEM_ESQ - MARGEM_DIR;

                PdfContentByte over = stamper.getOverContent(i);

                if (logo != null) {
                    Image img = Image.getInstance(logo);
                    img.setAbsolutePosition(MARGEM_ESQ, topo - LOGO_MARGEM);
                    img.scaleToFit(LOGO_TAMANHO, LOGO_TAMANHO);
                    over.addImage(img);
                }

                float textoX = MARGEM_ESQ + LOGO_TAMANHO + 6;
                float textoLarg = largUtil - LOGO_TAMANHO - 6;

                over.beginText();
                over.setFontAndSize(bf, 10);
                over.showTextAligned(Element.ALIGN_CENTER, linha1,
                    textoX + textoLarg / 2, topo - 20, 0);
                over.setFontAndSize(bf, 10);
                over.showTextAligned(Element.ALIGN_CENTER, linha2,
                    textoX + textoLarg / 2, topo - 35, 0);
                over.endText();

                over.setLineWidth(0.5f);
                over.setColorStroke(new Color(180, 180, 180));
                over.moveTo(MARGEM_ESQ, topo - 44);
                over.lineTo(largura - MARGEM_DIR, topo - 44);
                over.stroke();

                over.beginText();
                over.setFontAndSize(bf, 9);
                over.showTextAligned(Element.ALIGN_RIGHT,
                    "Pagina " + i + " de " + totalPaginas,
                    pageSize.getWidth() - MARGEM_DIR, 22, 0);
                over.endText();
            }

            stamper.close();
            reader.close();
            return baos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao estampar cabecalho do PDF", e);
        }
    }
}
