package br.gov.saude.sgpur.domain;

/**
 * Situacao de uma {@link SolicitacaoOnline} (pedido enviado pelo portal do
 * solicitante, ainda nao convertido em {@link Processo}).
 */
public enum StatusSolicitacaoOnline {
    ENVIADA("Enviada, aguardando triagem"),
    CONVERTIDA("Convertida em processo"),
    DEVOLVIDA("Devolvida para correcao"),
    CANCELADA("Cancelada pelo solicitante");

    private final String descricao;

    StatusSolicitacaoOnline(String descricao) {
        this.descricao = descricao;
    }

    public String getDescricao() {
        return descricao;
    }
}
