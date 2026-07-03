package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.domain.Parecer;
import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.StatusProcesso;
import br.gov.saude.sgpur.service.TempoRespostaService.ResumoTempo;
import br.gov.saude.sgpur.service.TempoRespostaService.TempoMembro;
import com.lowagie.text.*;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gera o Relatorio Geral (anual) de Urgencia Renal em PDF: um resumo com a
 * contagem por status do ano e a lista completa dos processos daquele ano.
 *
 * Mantem o mesmo padrao visual do {@link RelatorioService} (capa institucional,
 * barras de secao azuis, tabelas com bordas claras).
 */
@Service
public class RelatorioAnualService {

    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private static final Color AZUL = new Color(13, 110, 253);
    private static final Color CINZA = new Color(108, 117, 125);
    private static final Color BORDA = new Color(222, 226, 230);

    private final TempoRespostaService tempoRespostaService;

    public RelatorioAnualService(TempoRespostaService tempoRespostaService) {
        this.tempoRespostaService = tempoRespostaService;
    }

    /**
     * Gera o PDF do relatorio anual.
     *
     * @param ano       ano de referencia
     * @param processos processos do ano (ja ordenados por sequencial)
     */
    public byte[] gerar(int ano, List<Processo> processos) {
        Document doc = new Document(PageSize.A4, 36, 36, 46, 36);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            // Cabecalho institucional repetido em toda pagina a partir da 2a
            // (a capa ja tem o bloco institucional completo). Padrao usado nos
            // demais documentos do sistema (Oficio, Relatorio Final, carimbo
            // do avaliador): "Central de Transplantes do Estado do Rio Grande
            // do Sul - URGENCIA RENAL".
            writer.setPageEvent(new CabecalhoPagina(ano));
            doc.open();

            adicionarCapa(doc, ano, processos);
            doc.newPage();

            Font fSecao = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, Color.WHITE);

            // Pareceres respondidos do ano (mesmo criterio da query
            // findRespondidosComDatas: resultado, dataEnvio e dataResposta
            // preenchidos), para o indicador de tempo de resposta.
            Map<Long, String> nomePorMembro = new LinkedHashMap<>();
            List<Parecer> pareceresRespondidos = new java.util.ArrayList<>();
            for (Processo p : processos) {
                for (Parecer par : p.getPareceres()) {
                    if (par.getResultado() != null && par.getDataEnvio() != null
                        && par.getDataResposta() != null) {
                        pareceresRespondidos.add(par);
                        MembroUrgenciaRenal m = par.getMembro();
                        if (m != null) {
                            nomePorMembro.putIfAbsent(m.getId(), m.getRotulo());
                        }
                    }
                }
            }
            ResumoTempo tempoAno = tempoRespostaService.calcularDe(pareceresRespondidos);

            // 1. Resumo do ano
            secao(doc, fSecao, "1. Resumo do ano " + ano);
            doc.add(tabelaResumo(processos, tempoAno));

            // 2. Tempo de resposta por avaliador
            secao(doc, fSecao, "2. Tempo de resposta por avaliador");
            doc.add(tabelaTempoPorAvaliador(tempoAno, nomePorMembro));

            // 3. Lista completa
            secao(doc, fSecao, "3. Lista de processos do ano " + ano);
            doc.add(tabelaLista(processos));

            Paragraph rodape = new Paragraph(
                "Documento gerado automaticamente pelo SAUR - Sistema de Avaliacao de Urgencia Renal em "
                    + LocalDate.now().format(DATA) + ".",
                FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 8, CINZA));
            rodape.setAlignment(Element.ALIGN_CENTER);
            rodape.setSpacingBefore(16);
            doc.add(rodape);

            doc.close();
            return out.toByteArray();
        } catch (DocumentException e) {
            throw new IllegalStateException("Falha ao gerar o relatorio anual PDF", e);
        }
    }

    // -----------------------------------------------------------------------
    // Capa
    // -----------------------------------------------------------------------

    private void adicionarCapa(Document doc, int ano, List<Processo> processos) throws DocumentException {
        Font fOrgao = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13, Color.BLACK);
        Font fSubOrgao = FontFactory.getFont(FontFactory.HELVETICA, 12, Color.BLACK);
        Font fUrgencia = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14, AZUL);
        Font fTituloDoc = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18, Color.BLACK);
        Font fSub = FontFactory.getFont(FontFactory.HELVETICA, 11, CINZA);

        Paragraph espaco = new Paragraph(" ");
        espaco.setSpacingAfter(80);
        doc.add(espaco);

        Paragraph orgao = new Paragraph("Central de Transplantes do Estado do Rio Grande do Sul", fOrgao);
        orgao.setAlignment(Element.ALIGN_CENTER);
        doc.add(orgao);

        Paragraph secretaria = new Paragraph("SECRETARIA DE SAUDE", fSubOrgao);
        secretaria.setAlignment(Element.ALIGN_CENTER);
        doc.add(secretaria);

        Paragraph urgencia = new Paragraph("URGENCIA RENAL", fUrgencia);
        urgencia.setAlignment(Element.ALIGN_CENTER);
        urgencia.setSpacingAfter(36);
        doc.add(urgencia);

        PdfPTable separador = new PdfPTable(1);
        separador.setWidthPercentage(50);
        separador.setSpacingAfter(36);
        PdfPCell linhaSep = new PdfPCell(new Phrase(" "));
        linhaSep.setBorderWidthBottom(1.5f);
        linhaSep.setBorderWidthTop(0);
        linhaSep.setBorderWidthLeft(0);
        linhaSep.setBorderWidthRight(0);
        linhaSep.setBorderColor(AZUL);
        linhaSep.setPadding(0);
        separador.addCell(linhaSep);
        doc.add(separador);

        Paragraph tituloDoc = new Paragraph("RELATORIO GERAL DE URGENCIA RENAL - ANO " + ano, fTituloDoc);
        tituloDoc.setAlignment(Element.ALIGN_CENTER);
        tituloDoc.setSpacingAfter(16);
        doc.add(tituloDoc);

        Paragraph resumoCapa = new Paragraph(
            "Total de processos no ano: " + processos.size()
                + "  |  Emitido em " + java.time.LocalDateTime.now().format(DATA_HORA), fSub);
        resumoCapa.setAlignment(Element.ALIGN_CENTER);
        doc.add(resumoCapa);
    }

    // -----------------------------------------------------------------------
    // Resumo (contagem por status)
    // -----------------------------------------------------------------------

    private PdfPTable tabelaResumo(List<Processo> processos, ResumoTempo tempoAno) {
        Map<StatusProcesso, Integer> contagem = new EnumMap<>(StatusProcesso.class);
        for (Processo p : processos) {
            contagem.merge(p.getStatus(), 1, Integer::sum);
        }
        // ENVIADO e EM_ANALISE sao apresentados juntos como "Em andamento".
        int total = processos.size();
        int solicitado = contagem.getOrDefault(StatusProcesso.SOLICITADO, 0);
        int emAndamento = contagem.getOrDefault(StatusProcesso.ENVIADO, 0)
            + contagem.getOrDefault(StatusProcesso.EM_ANALISE, 0);
        int solicitaInfo = contagem.getOrDefault(StatusProcesso.SOLICITA_INFORMACAO, 0);
        int deferido = contagem.getOrDefault(StatusProcesso.DEFERIDO, 0);
        int indeferido = contagem.getOrDefault(StatusProcesso.INDEFERIDO, 0);
        int cancelado = contagem.getOrDefault(StatusProcesso.CANCELADO, 0);

        int decididos = deferido + indeferido;
        String percentDeferimento = decididos == 0
            ? "-" : Math.round(deferido * 100.0 / decididos) + "%";

        PdfPTable t = new PdfPTable(new float[]{6, 2});
        t.setWidthPercentage(60);
        t.setSpacingBefore(6);
        t.setHorizontalAlignment(Element.ALIGN_LEFT);

        linhaResumo(t, "Total de processos", String.valueOf(total), true);
        linhaResumo(t, "Solicitados (aguardando envio)", String.valueOf(solicitado), false);
        linhaResumo(t, "Em andamento (enviados / em analise)", String.valueOf(emAndamento), false);
        linhaResumo(t, "Solicita informacao", String.valueOf(solicitaInfo), false);
        linhaResumo(t, "Deferidos", String.valueOf(deferido), false);
        linhaResumo(t, "Indeferidos", String.valueOf(indeferido), false);
        linhaResumo(t, "Cancelados", String.valueOf(cancelado), false);
        linhaResumo(t, "% de deferimento (sobre os decididos)", percentDeferimento, true);
        linhaResumo(t, "Tempo medio de resposta dos avaliadores",
            TempoRespostaService.formatarDias(tempoAno.mediaGeralDias()), true);
        linhaResumo(t, "Pareceres fora do prazo (meta " + tempoAno.prazoDias() + " dias corridos)",
            tempoAno.foraDoPrazo() + " de " + tempoAno.totalAvaliados(), false);
        return t;
    }

    // -----------------------------------------------------------------------
    // Tempo de resposta por avaliador
    // -----------------------------------------------------------------------

    private PdfPTable tabelaTempoPorAvaliador(ResumoTempo tempoAno, Map<Long, String> nomePorMembro) {
        PdfPTable t = new PdfPTable(new float[]{4, 2, 2, 2});
        t.setWidthPercentage(80);
        t.setSpacingBefore(6);
        t.setHeaderRows(1);
        cabecalho(t, "Avaliador", "Respondidos", "Tempo medio", "Fora do prazo");

        if (tempoAno.porMembro().isEmpty()) {
            PdfPCell vazio = new PdfPCell(new Phrase("Nenhum parecer respondido neste ano.",
                FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 9, CINZA)));
            vazio.setColspan(4);
            vazio.setPadding(8);
            vazio.setHorizontalAlignment(Element.ALIGN_CENTER);
            vazio.setBorderColor(BORDA);
            t.addCell(vazio);
            return t;
        }

        for (var e : tempoAno.porMembro().entrySet()) {
            TempoMembro tm = e.getValue();
            celula(t, nomePorMembro.getOrDefault(e.getKey(), "Membro #" + e.getKey()));
            celula(t, String.valueOf(tm.avaliados()));
            celula(t, TempoRespostaService.formatarDias(tm.mediaDias()));
            celula(t, String.valueOf(tm.foraDoPrazo()));
        }
        return t;
    }

    private void linhaResumo(PdfPTable t, String rotulo, String valor, boolean destaque) {
        Font fr = FontFactory.getFont(destaque ? FontFactory.HELVETICA_BOLD : FontFactory.HELVETICA, 10,
            destaque ? Color.BLACK : CINZA);
        Font fv = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, Color.BLACK);
        PdfPCell c1 = new PdfPCell(new Phrase(rotulo, fr));
        PdfPCell c2 = new PdfPCell(new Phrase(valor, fv));
        c2.setHorizontalAlignment(Element.ALIGN_RIGHT);
        for (PdfPCell c : new PdfPCell[]{c1, c2}) {
            c.setPadding(5);
            c.setBorderColor(BORDA);
        }
        t.addCell(c1);
        t.addCell(c2);
    }

    // -----------------------------------------------------------------------
    // Lista completa
    // -----------------------------------------------------------------------

    private PdfPTable tabelaLista(List<Processo> processos) {
        PdfPTable t = new PdfPTable(new float[]{1.4f, 2.6f, 1.6f, 1.6f, 3, 3, 3, 1.6f, 1.6f});
        t.setWidthPercentage(100);
        t.setSpacingBefore(6);
        t.setHeaderRows(1);
        cabecalho(t, "No/Ano", "Paciente", "RGCT", "Status",
            "Medico 1", "Medico 2", "Medico 3", "Cadastro", "Decisao");

        if (processos.isEmpty()) {
            PdfPCell vazio = new PdfPCell(new Phrase("Nenhum processo neste ano.",
                FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 9, CINZA)));
            vazio.setColspan(9);
            vazio.setPadding(8);
            vazio.setHorizontalAlignment(Element.ALIGN_CENTER);
            vazio.setBorderColor(BORDA);
            t.addCell(vazio);
            return t;
        }

        for (Processo p : processos) {
            celula(t, nvl(p.getNumero()));
            celula(t, nvl(p.getPacienteNome()));
            celula(t, nvl(p.getPacienteRgct()));
            celula(t, p.getStatus().getDescricao());

            List<Parecer> pareceres = p.getPareceres();
            for (int i = 0; i < 3; i++) {
                if (i < pareceres.size()) {
                    Parecer par = pareceres.get(i);
                    String res = par.getResultado() != null
                        ? par.getResultado().getDescricao() : "Aguardando";
                    celula(t, par.getMembro().getRotulo() + " (" + res + ")");
                } else {
                    celula(t, "-");
                }
            }

            celula(t, p.getDataCadastro() != null ? p.getDataCadastro().format(DATA) : "-");
            celula(t, p.getDataDecisao() != null ? p.getDataDecisao().format(DATA) : "-");
        }
        return t;
    }

    // -----------------------------------------------------------------------
    // Helpers de estilo (espelham o RelatorioService)
    // -----------------------------------------------------------------------

    private void secao(Document doc, Font fSecao, String texto) throws DocumentException {
        PdfPTable barra = new PdfPTable(1);
        barra.setWidthPercentage(100);
        barra.setSpacingBefore(12);
        barra.setSpacingAfter(2);
        PdfPCell c = new PdfPCell(new Phrase(texto, fSecao));
        c.setBackgroundColor(AZUL);
        c.setPadding(5);
        c.setBorder(Rectangle.NO_BORDER);
        barra.addCell(c);
        doc.add(barra);
    }

    private void cabecalho(PdfPTable t, String... cols) {
        Font f = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, Color.WHITE);
        for (String col : cols) {
            PdfPCell c = new PdfPCell(new Phrase(col, f));
            c.setBackgroundColor(CINZA);
            c.setPadding(4);
            c.setHorizontalAlignment(Element.ALIGN_CENTER);
            t.addCell(c);
        }
    }

    private void celula(PdfPTable t, String texto) {
        Font f = FontFactory.getFont(FontFactory.HELVETICA, 8, Color.BLACK);
        PdfPCell c = new PdfPCell(new Phrase(texto, f));
        c.setPadding(4);
        c.setHorizontalAlignment(Element.ALIGN_LEFT);
        c.setBorderColor(BORDA);
        t.addCell(c);
    }

    private String nvl(String s) {
        return (s == null || s.isBlank()) ? "-" : s;
    }

    // -----------------------------------------------------------------------
    // Cabecalho repetido em toda pagina (a partir da 2a) - mesmo padrao
    // institucional usado no Oficio, Relatorio Final e no carimbo do avaliador.
    // -----------------------------------------------------------------------

    private static final class CabecalhoPagina extends PdfPageEventHelper {
        private final int ano;
        private final Font fLinha1 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, CINZA);
        private final Font fLinha2 = FontFactory.getFont(FontFactory.HELVETICA, 7, CINZA);

        CabecalhoPagina(int ano) {
            this.ano = ano;
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            if (writer.getPageNumber() == 1) {
                return; // capa ja tem o bloco institucional completo
            }
            PdfContentByte cb = writer.getDirectContent();
            Rectangle pagina = document.getPageSize();
            float centroX = (pagina.getLeft() + pagina.getRight()) / 2f;
            float topoY = pagina.getTop() - 20;
            ColumnText.showTextAligned(cb, Element.ALIGN_CENTER,
                new Phrase("Central de Transplantes do Estado do Rio Grande do Sul - URGENCIA RENAL", fLinha1),
                centroX, topoY, 0);
            ColumnText.showTextAligned(cb, Element.ALIGN_CENTER,
                new Phrase("Relatorio Geral de Urgencia Renal - Ano " + ano, fLinha2),
                centroX, topoY - 10, 0);
        }
    }
}
