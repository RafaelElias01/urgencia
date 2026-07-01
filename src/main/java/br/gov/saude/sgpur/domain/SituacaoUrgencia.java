package br.gov.saude.sgpur.domain;

/**
 * Situacao de um registro no Controle de Urgencias para Transplante Renal.
 * <p>
 * ATIVA     = dentro do prazo de 30 dias, aguardando renovacao ou expiracao
 * RENOVADA  = foi renovada por mais 30 dias (receptor segue ativo na lista unica)
 * EXPIRADA  = venceu sem renovacao
 * CANCELADA = cancelada (receptor nao esta mais ativo na lista)
 */
public enum SituacaoUrgencia {

    ATIVA("Ativa"),
    RENOVADA("Renovada"),
    EXPIRADA("Expirada"),
    CANCELADA("Cancelada");

    private final String descricao;

    SituacaoUrgencia(String descricao) {
        this.descricao = descricao;
    }

    public String getDescricao() {
        return descricao;
    }
}
