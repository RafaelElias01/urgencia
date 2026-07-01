package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.Processo;
import com.lowagie.text.*;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * Gera o Oficio de Indeferimento em PDF (documento formal enviado ao
 * solicitante). Modelo com cabecalho, referencia ao processo, nome completo do
 * paciente, motivo e fecho/assinatura (placeholders editaveis).
 */
@Service
public class OficioService {

    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");

    public byte[] gerar(Processo p) {
        Document doc = new Document(PageSize.A4, 56, 56, 56, 56);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfWriter.getInstance(doc, out);
            doc.open();

            Font fCab = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, Color.BLACK);
            Font fCabSub = FontFactory.getFont(FontFactory.HELVETICA, 10, new Color(80, 80, 80));
            Font fTitulo = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, Color.BLACK);
            Font fCorpo = FontFactory.getFont(FontFactory.HELVETICA, 11, Color.BLACK);

            // Cabecalho institucional
            Paragraph cab = new Paragraph("GOVERNO DO ESTADO DO RIO GRANDE DO SUL", fCab);
            cab.setAlignment(Element.ALIGN_CENTER);
            doc.add(cab);
            Paragraph cab2 = new Paragraph("URGENCIA RENAL", fCabSub);
            cab2.setAlignment(Element.ALIGN_CENTER);
            cab2.setSpacingAfter(24);
            doc.add(cab2);

            // Numero do oficio e data
            LocalDate dataEmissao = p.getDataEmissaoOficio() != null ? p.getDataEmissaoOficio() : LocalDate.now();
            Paragraph titulo = new Paragraph("OFICIO DE INDEFERIMENTO - " + p.identificacao(), fTitulo);
            titulo.setSpacingAfter(6);
            doc.add(titulo);

            String mes = dataEmissao.getMonth().getDisplayName(TextStyle.FULL, PT_BR);
            Paragraph local = new Paragraph(
                "Local, " + dataEmissao.getDayOfMonth() + " de " + mes + " de " + dataEmissao.getYear() + ".",
                fCorpo);
            local.setAlignment(Element.ALIGN_RIGHT);
            local.setSpacingAfter(18);
            doc.add(local);

            // Destinatario
            Paragraph dest = new Paragraph("Ao(A) solicitante: " + p.getSolicitanteEquipe(), fCorpo);
            dest.setSpacingAfter(12);
            doc.add(dest);

            // Corpo
            String motivo = (p.getMotivoIndeferimento() == null || p.getMotivoIndeferimento().isBlank())
                ? "(motivo nao informado)" : p.getMotivoIndeferimento();
            String texto = """
                Prezado(a) Senhor(a),

                Em referencia ao Processo de Urgencia Renal n. %s, referente ao(a) paciente \
                %s, comunicamos que, apos analise dos pareceres da equipe de Urgencia Renal, \
                o pedido foi INDEFERIDO.

                Motivo do indeferimento: %s

                Permanecemos a disposicao para os esclarecimentos que se fizerem necessarios.
                """.formatted(p.getNumero(), p.getPacienteNome(), motivo);
            Paragraph corpo = new Paragraph(texto, fCorpo);
            corpo.setAlignment(Element.ALIGN_JUSTIFIED);
            corpo.setSpacingAfter(28);
            doc.add(corpo);

            // Fecho / assinatura (placeholders)
            Paragraph fecho = new Paragraph("Atenciosamente,", fCorpo);
            fecho.setSpacingAfter(36);
            doc.add(fecho);

            Paragraph assinatura = new Paragraph("____________________________________\n"
                + "Responsavel - Equipe de Urgencia Renal\nSecretaria de Saude", fCorpo);
            assinatura.setAlignment(Element.ALIGN_CENTER);
            doc.add(assinatura);

            doc.close();
            return out.toByteArray();
        } catch (DocumentException e) {
            throw new IllegalStateException("Falha ao gerar o oficio de indeferimento", e);
        }
    }
}
