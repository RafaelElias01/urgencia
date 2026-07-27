package br.gov.saude.sgpur.domain;

/**
 * Situacao de uma {@link SolicitacaoOnline} (pedido enviado pelo portal do
 * solicitante, ainda nao convertido em {@link Processo}).
 */
public enum StatusSolicitacaoOnline {
    ENVIADA("Enviada, aguardando triagem"),
    CONVERTIDA("Convertida em processo"),
    DEVOLVIDA("Devolvida para correcao"),
    CANCELADA("Cancelada pelo solicitante"),
    PROCESSO_EXCLUIDO("Processo excluido pela equipe"),
    APROVADA("Aprovada"),
    REPROVADA("Reprovada");

    private final String descricao;

    StatusSolicitacaoOnline(String descricao) {
        this.descricao = descricao;
    }

    public String getDescricao() {
        return descricao;
    }

    /** Bootstrap-icon (sem o prefixo "bi-") usado no badge do status, mesmo padrao de {@code StatusProcesso}. */
    public String getBadgeIcone() {
        return switch (this) {
            case ENVIADA -> "hourglass-split";
            case CONVERTIDA -> "check-circle-fill";
            case DEVOLVIDA -> "arrow-return-left";
            case CANCELADA -> "slash-circle-fill";
            case PROCESSO_EXCLUIDO -> "exclamation-triangle-fill";
            case APROVADA -> "check-circle-fill";
            case REPROVADA -> "x-circle-fill";
        };
    }

    /** Classe de cor do Bootstrap (badge bg-*), mesmo padrao de {@code StatusProcesso}. */
    public String getBootstrapBadge() {
        return switch (this) {
            case ENVIADA -> "bg-warning text-dark";
            case CONVERTIDA -> "bg-success";
            case DEVOLVIDA -> "bg-danger";
            case CANCELADA -> "bg-secondary";
            case PROCESSO_EXCLUIDO -> "bg-danger";
            case APROVADA -> "bg-success";
            case REPROVADA -> "bg-danger";
        };
    }
}
