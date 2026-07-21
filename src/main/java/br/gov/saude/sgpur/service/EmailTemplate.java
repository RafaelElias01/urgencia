package br.gov.saude.sgpur.service;

/**
 * Texto de e-mail pronto para copiar/colar, referente a uma etapa do processo.
 *
 * @param chave       identificador da etapa (ex.: "medicos", "deferido")
 * @param titulo      rotulo exibido na tela
 * @param icone       bootstrap-icon (sem o "bi-")
 * @param assunto     assunto sugerido do e-mail
 * @param corpo       corpo do e-mail ja preenchido
 * @param requerEnvio true indica que o template so deve ser exibido apos o
 *                    envio aos avaliadores estar registrado (dataEnvio preenchida)
 */
public record EmailTemplate(String chave, String titulo, String icone,
                            String assunto, String corpo, boolean requerEnvio) {

    /** Construtor de compatibilidade para templates que nao requerem envio previo. */
    public EmailTemplate(String chave, String titulo, String icone,
                         String assunto, String corpo) {
        this(chave, titulo, icone, assunto, corpo, false);
    }
}
