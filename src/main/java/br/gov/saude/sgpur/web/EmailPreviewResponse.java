package br.gov.saude.sgpur.web;

import java.util.List;

/**
 * Pre-visualizacao de e-mail(s) antes do envio real. Devolve exatamente os
 * destinatarios e o conteudo que serao enviados, para que o operador confira
 * num modal e confirme. {@code ok=false} traz {@code erro} (ex.: anexo
 * obrigatorio ainda ausente) e nenhuma mensagem.
 * <p>
 * {@code mensagens} tem 1 item para envios individuais e N itens para envios
 * em lote (um por destinatario, cada um com seu corpo personalizado).
 */
public record EmailPreviewResponse(boolean ok, String erro, List<Mensagem> mensagens) {

    public static EmailPreviewResponse ok(List<Mensagem> mensagens) {
        return new EmailPreviewResponse(true, null, mensagens);
    }

    public static EmailPreviewResponse erro(String erro) {
        return new EmailPreviewResponse(false, erro, List.of());
    }

    /** Uma mensagem de e-mail concreta: destinatario(s), assunto e corpo. */
    public record Mensagem(String destinatarios, String assunto, String corpo) {}
}
