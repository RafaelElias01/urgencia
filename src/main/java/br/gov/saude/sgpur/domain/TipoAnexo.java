package br.gov.saude.sgpur.domain;

/**
 * Categoria do documento/e-mail anexado ao processo. Reflete as etapas do
 * fluxo, que sao conduzidas por e-mail (recebimento, envio, votos, resposta).
 */
public enum TipoAnexo {
    SOLICITACAO_RECEBIDA("E-mail/documento de solicitacao recebida"),
    DOCUMENTO_PACIENTE("Documento do paciente"),
    EMAIL_ENVIADO_AVALIADORES("Copia do e-mail enviado aos avaliadores"),
    EMAIL_PARECER_RECEBIDO("Copia do e-mail de parecer recebido do avaliador"),
    RESPOSTA_AVALIADOR("Copia do e-mail de resposta de avaliador especifico"),
    OFICIO_INDEFERIMENTO("Oficio de indeferimento"),
    EMAIL_RESPOSTA_SOLICITANTE("Copia do e-mail de resposta ao solicitante"),
    RELATORIO_FINAL("Relatorio final do processo"),
    OUTRO("Outro");

    private final String descricao;

    TipoAnexo(String descricao) {
        this.descricao = descricao;
    }

    public String getDescricao() {
        return descricao;
    }
}
