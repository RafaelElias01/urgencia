package br.gov.saude.sgpur.web;

/**
 * Resposta das chamadas AJAX de assistencia por IA (sugestao de motivo,
 * resumo de documento, revisao de e-mail). {@code texto} vem preenchido no
 * sucesso; {@code erro} no fracasso (chave nao configurada, falha na API, etc).
 */
public record IaTextoResponse(String texto, String erro) {

    public static IaTextoResponse sucesso(String texto) {
        return new IaTextoResponse(texto, null);
    }

    public static IaTextoResponse erro(String mensagem) {
        return new IaTextoResponse(null, mensagem);
    }
}
