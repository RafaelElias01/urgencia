package br.gov.saude.sgpur.web;

/**
 * Resposta generica das acoes AJAX que disparam efeitos colaterais (envio de
 * e-mail, etc). {@code ok} indica sucesso/falha; {@code mensagem} e exibida
 * ao operador em ambos os casos.
 */
public record AcaoResponse(boolean ok, String mensagem) {

    public static AcaoResponse sucesso(String mensagem) {
        return new AcaoResponse(true, mensagem);
    }

    public static AcaoResponse erro(String mensagem) {
        return new AcaoResponse(false, mensagem);
    }
}
