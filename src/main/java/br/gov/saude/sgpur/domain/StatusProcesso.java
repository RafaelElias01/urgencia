package br.gov.saude.sgpur.domain;

/**
 * Situacao administrativa do Processo de Urgencia Renal, refletindo o fluxo
 * real (planilha) em 10 etapas:
 *
 *   SOLICITADO -> ENVIADO -> { DEFERIDO, INDEFERIDO, SOLICITA_INFORMACAO }
 *   (+ CANCELADO a qualquer momento).
 *
 * O processo nasce SOLICITADO (e-mail recebido, registro criado com os 3
 * medicos), passa a ENVIADO quando a solicitacao e enviada aos avaliadores e
 * termina em uma decisao. SOLICITA_INFORMACAO e um estado intermediario (um
 * medico pediu mais dados) que ainda nao e final.
 *
 * Compatibilidade: EM_ANALISE e mantido como sinonimo "legado" de ENVIADO,
 * para que processos antigos (gravados antes desta expansao) continuem
 * validos e sejam tratados como "em andamento" (nao finalizados).
 */
public enum StatusProcesso {
    SOLICITADO("Solicitado"),
    ENVIADO("Enviado"),
    EM_ANALISE("Em analise"),
    SOLICITA_INFORMACAO("Solicita informacao"),
    DEFERIDO("Deferido"),
    INDEFERIDO("Indeferido"),
    CANCELADO("Cancelado");

    private final String descricao;

    StatusProcesso(String descricao) {
        this.descricao = descricao;
    }

    public String getDescricao() {
        return descricao;
    }

    /**
     * Estados finais (encerram o processo): DEFERIDO, INDEFERIDO e CANCELADO.
     * SOLICITADO, ENVIADO, EM_ANALISE e SOLICITA_INFORMACAO ainda estao em
     * andamento.
     */
    public boolean isFinalizado() {
        return this == DEFERIDO || this == INDEFERIDO || this == CANCELADO;
    }

    /** Indica se o processo ainda esta em andamento (nao finalizado). */
    public boolean isEmAndamento() {
        return !isFinalizado();
    }

    /** Bootstrap-icon (sem o prefixo "bi-") usado no badge do status. */
    public String getBadgeIcone() {
        return switch (this) {
            case SOLICITADO -> "inbox-fill";
            case ENVIADO -> "send-fill";
            case EM_ANALISE -> "hourglass-split";
            case SOLICITA_INFORMACAO -> "question-circle-fill";
            case DEFERIDO -> "check-circle-fill";
            case INDEFERIDO -> "x-circle-fill";
            case CANCELADO -> "slash-circle-fill";
        };
    }

    /** Classe de cor do Bootstrap (badge bg-*) para as telas que usam Bootstrap. */
    public String getBootstrapBadge() {
        return switch (this) {
            case SOLICITADO -> "bg-secondary";
            case ENVIADO -> "bg-primary";
            case EM_ANALISE -> "bg-warning text-dark";
            case SOLICITA_INFORMACAO -> "bg-info text-dark";
            case DEFERIDO -> "bg-success";
            case INDEFERIDO -> "bg-danger";
            case CANCELADO -> "bg-dark";
        };
    }
}
