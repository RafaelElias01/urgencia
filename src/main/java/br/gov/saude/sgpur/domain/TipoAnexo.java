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
    /**
     * STAGING (trava de anonimizacao): documento que veio do Portal do
     * Solicitante e ainda NAO foi revisado/anonimizado pelo operador. Este tipo
     * <b>nunca</b> entra no PDF consolidado enviado aos 3 avaliadores
     * ({@code RegistroEnvioService} so funde {@link #DOCUMENTO_CLINICO_AVALIADOR})
     * e nao satisfaz {@code ProcessoValidator.validarRegistroEnvio}. Vira
     * {@link #DOCUMENTO_CLINICO_AVALIADOR} apenas por acao explicita e auditada
     * do operador ("Confirmo que este documento foi anonimizado", endpoint
     * {@code POST /processos/{id}/documento-clinico/{anexoId}/confirmar-anonimizacao}).
     *
     * <p><b>Processos anteriores a esta trava</b> tiveram o documento do portal
     * copiado direto como {@link #DOCUMENTO_CLINICO_AVALIADOR}: aquele tipo
     * continua valido e elegivel ao envio, entao esses processos seguem
     * funcionando normalmente - a trava so vale para conversoes novas.
     */
    DOCUMENTO_PORTAL_NAO_ANONIMIZADO(
        "Documento do Portal do Solicitante pendente de anonimizacao (nao vai aos avaliadores)"),
    DOCUMENTO_PACIENTE("Documento do paciente"),
    EMAIL_ENVIADO_AVALIADORES("Copia do e-mail enviado aos avaliadores"),
    EMAIL_PARECER_RECEBIDO("Copia do e-mail de parecer recebido do avaliador"),
    RESPOSTA_AVALIADOR("Copia do e-mail de resposta de avaliador especifico"),
    ANEXO_AVALIADOR("Documento anexado pelo avaliador junto ao parecer (Portal do Avaliador)"),
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
