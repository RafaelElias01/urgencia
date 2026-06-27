package br.gov.saude.sgpur.service;

/**
 * Texto de e-mail pronto para copiar/colar, referente a uma etapa do processo.
 *
 * @param chave    identificador da etapa (ex.: "medicos", "deferido")
 * @param titulo   rotulo exibido na tela
 * @param icone    bootstrap-icon (sem o "bi-")
 * @param assunto  assunto sugerido do e-mail
 * @param corpo    corpo do e-mail ja preenchido
 */
public record EmailTemplate(String chave, String titulo, String icone,
                            String assunto, String corpo) {
}
