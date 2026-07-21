package br.gov.saude.sgpur.domain;

/**
 * Categoria do documento/e-mail anexado ao processo. Reflete as etapas do
 * fluxo, que sao conduzidas por e-mail (recebimento, envio, votos, resposta).
 */
public enum TipoAnexo {
    SOLICITACAO_AVALIADOR("Solicitacao de avaliacao gerada pelo sistema"),
    SOLICITACAO_RECEBIDA("E-mail/documento de solicitacao recebida"),
    CAPA_PROCESSO("Capa do processo (dados do solicitante e medicos)"),
    DOCUMENTO_CLINICO_AVALIADOR("Documento clinico anonimizado para os avaliadores"),
    DOCUMENTO_PACIENTE("Documento do paciente"),
    EMAIL_ENVIADO_AVALIADORES("Copia do e-mail enviado aos avaliadores"),
    EMAIL_PARECER_RECEBIDO("Copia do e-mail de parecer recebido do avaliador"),
    RESPOSTA_AVALIADOR("Copia do e-mail de resposta de avaliador especifico"),
    INFO_COMPLEMENTAR("Pedido/resposta de informacao complementar (solicitante)"),
    OFICIO_INDEFERIMENTO("Oficio de indeferimento"),
    COMPROVANTE_SNT("Comprovante de insercao da urgencia renal no SNT"),
    EMAIL_RESPOSTA_SOLICITANTE("Copia do e-mail de resposta ao solicitante"),
    COMPROVANTE_ENVIO_SOLICITANTE("Comprovante de envio da resposta ao solicitante (print/PDF do e-mail)"),
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
