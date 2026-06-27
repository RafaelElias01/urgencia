package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.Anexo;
import br.gov.saude.sgpur.domain.Parecer;
import br.gov.saude.sgpur.domain.Processo;
import com.lowagie.text.*;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;

/**
 * Gera o Relatorio Final do Processo de Urgencia Renal em PDF - documento
 * oficial para arquivamento, impressao e auditoria.
 */
@Service
public class RelatorioService {

    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private static final Color AZUL = new Color(13, 110, 253);
    private static final Color CINZA = new Color(108, 117, 125);

    private final FluxoProcessoService fluxoService;
    private final ProcessoService processoService;

    public RelatorioService(FluxoProcessoService fluxoService, ProcessoService processoService) {
        this.fluxoService = fluxoService;
        this.processoService = processoService;
    }

    public byte[] gerar(Processo p) {
        Document doc = new Document(PageSize.A4, 40, 40, 50, 40);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfWriter.getInstance(doc, out);
            doc.open();

            Font fTitulo = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 15, AZUL);
            Font fSub = FontFactory.getFont(FontFactory.HELVETICA, 9, CINZA);
            Font fSecao = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, Color.WHITE);

            // Cabecalho
            Paragraph titulo = new Paragraph("RELATORIO FINAL - PROCESSO DE URGENCIA RENAL", fTitulo);
            titulo.setAlignment(Element.ALIGN_CENTER);
            doc.add(titulo);

            Paragraph sub = new Paragraph(
                p.identificacao() + "  |  Situacao: " + p.getStatus().getDescricao()
                    + "  |  Emitido em " + java.time.LocalDateTime.now().format(DATA_HORA), fSub);
            sub.setAlignment(Element.ALIGN_CENTER);
            sub.setSpacingAfter(14);
            doc.add(sub);

            // Dados da solicitacao
            secao(doc, fSecao, "1. Dados da solicitacao");
            PdfPTable t1 = tabelaDados();
            linha(t1, "Numero do processo", p.getNumero());
            linha(t1, "Paciente (receptor)", p.getPacienteNome());
            linha(t1, "RGCT / SNT", nvl(p.getPacienteRgct()));
            linha(t1, "Equipe solicitante", p.getSolicitanteEquipe());
            linha(t1, "E-mail do solicitante", nvl(p.getSolicitanteEmail()));
            linha(t1, "Data da situacao especial",
                p.getDataSituacaoEspecial() != null ? p.getDataSituacaoEspecial().format(DATA) : "-");
            linha(t1, "Data de cadastro",
                p.getDataCadastro() != null ? p.getDataCadastro().format(DATA_HORA) : "-");
            linha(t1, "Observacoes", nvl(p.getObservacoes()));
            doc.add(t1);

            // Pareceres
            secao(doc, fSecao, "2. Pareceres dos medicos (Urgencia Renal)");
            PdfPTable t2 = new PdfPTable(new float[]{4, 2, 2});
            t2.setWidthPercentage(100);
            t2.setSpacingBefore(4);
            cabecalho(t2, "Medico", "Parecer", "Data da resposta");
            for (Parecer par : p.getPareceres()) {
                celula(t2, par.getMembro().getRotulo(), Element.ALIGN_LEFT, false);
                celula(t2, par.getResultado() != null ? par.getResultado().getDescricao() : "Pendente",
                    Element.ALIGN_LEFT, false);
                celula(t2, par.getDataResposta() != null ? par.getDataResposta().format(DATA) : "-",
                    Element.ALIGN_LEFT, false);
            }
            doc.add(t2);
            Paragraph fav = new Paragraph(
                "Favoraveis: " + processoService.contarFavoraveis(p) + " (regra: "
                    + ProcessoService.FAVORAVEIS_PARA_DEFERIR + " de "
                    + ProcessoService.AVALIADORES_POR_PROCESSO + " defere o processo).",
                FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 9, CINZA));
            fav.setSpacingBefore(4);
            doc.add(fav);

            // Decisao
            secao(doc, fSecao, "3. Decisao final");
            PdfPTable t3 = tabelaDados();
            linha(t3, "Resultado", p.getStatus().getDescricao());
            linha(t3, "Data da decisao",
                p.getDataDecisao() != null ? p.getDataDecisao().format(DATA_HORA) : "-");
            linha(t3, "Motivo do indeferimento", nvl(p.getMotivoIndeferimento()));
            linha(t3, "Data de emissao do oficio",
                p.getDataEmissaoOficio() != null ? p.getDataEmissaoOficio().format(DATA) : "-");
            linha(t3, "Data de envio do oficio",
                p.getDataEnvioOficio() != null ? p.getDataEnvioOficio().format(DATA) : "-");
            linha(t3, "E-mail enviado ao solicitante", p.isEmailEnviadoSolicitante() ? "Sim" : "Nao");
            doc.add(t3);

            // Andamento (etapas)
            secao(doc, fSecao, "4. Andamento do processo");
            PdfPTable t4 = new PdfPTable(new float[]{1, 4, 5});
            t4.setWidthPercentage(100);
            t4.setSpacingBefore(4);
            cabecalho(t4, "Status", "Etapa", "Detalhe");
            for (EtapaFluxo e : fluxoService.montarEtapas(p)) {
                String marca = switch (e.estado()) {
                    case CONCLUIDA -> "[X]";
                    case ATUAL -> "[>]";
                    case PENDENTE -> "[ ]";
                };
                celula(t4, marca, Element.ALIGN_CENTER, false);
                celula(t4, e.titulo(), Element.ALIGN_LEFT, false);
                celula(t4, e.detalhe(), Element.ALIGN_LEFT, false);
            }
            doc.add(t4);

            // Anexos
            secao(doc, fSecao, "5. Relacao de anexos");
            if (p.getAnexos().isEmpty()) {
                doc.add(new Paragraph("Nenhum anexo registrado.",
                    FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 9, CINZA)));
            } else {
                PdfPTable t5 = new PdfPTable(new float[]{3, 4, 2});
                t5.setWidthPercentage(100);
                t5.setSpacingBefore(4);
                cabecalho(t5, "Tipo", "Arquivo", "Data");
                for (Anexo a : p.getAnexos()) {
                    celula(t5, a.getTipo().getDescricao(), Element.ALIGN_LEFT, false);
                    celula(t5, a.getNomeArquivo(), Element.ALIGN_LEFT, false);
                    celula(t5, a.getDataUpload() != null ? a.getDataUpload().format(DATA) : "-",
                        Element.ALIGN_LEFT, false);
                }
                doc.add(t5);
            }

            Paragraph rodape = new Paragraph(
                "Documento gerado automaticamente pelo SGPUR - Sistema de Gestao de Processos de Urgencia Renal.",
                FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 8, CINZA));
            rodape.setAlignment(Element.ALIGN_CENTER);
            rodape.setSpacingBefore(20);
            doc.add(rodape);

            doc.close();
            return out.toByteArray();
        } catch (DocumentException e) {
            throw new IllegalStateException("Falha ao gerar o relatorio PDF", e);
        }
    }

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

    private PdfPTable tabelaDados() {
        PdfPTable t = new PdfPTable(new float[]{3, 7});
        t.setWidthPercentage(100);
        t.setSpacingBefore(4);
        return t;
    }

    private void linha(PdfPTable t, String rotulo, String valor) {
        Font fr = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, CINZA);
        Font fv = FontFactory.getFont(FontFactory.HELVETICA, 9, Color.BLACK);
        PdfPCell c1 = new PdfPCell(new Phrase(rotulo, fr));
        PdfPCell c2 = new PdfPCell(new Phrase(valor, fv));
        for (PdfPCell c : new PdfPCell[]{c1, c2}) {
            c.setPadding(4);
            c.setBorderColor(new Color(222, 226, 230));
        }
        t.addCell(c1);
        t.addCell(c2);
    }

    private void cabecalho(PdfPTable t, String... cols) {
        Font f = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Color.WHITE);
        for (String col : cols) {
            PdfPCell c = new PdfPCell(new Phrase(col, f));
            c.setBackgroundColor(CINZA);
            c.setPadding(4);
            t.addCell(c);
        }
    }

    private void celula(PdfPTable t, String texto, int align, boolean bold) {
        Font f = FontFactory.getFont(bold ? FontFactory.HELVETICA_BOLD : FontFactory.HELVETICA, 9, Color.BLACK);
        PdfPCell c = new PdfPCell(new Phrase(texto, f));
        c.setPadding(4);
        c.setHorizontalAlignment(align);
        c.setBorderColor(new Color(222, 226, 230));
        t.addCell(c);
    }

    private String nvl(String s) {
        return (s == null || s.isBlank()) ? "-" : s;
    }
}
