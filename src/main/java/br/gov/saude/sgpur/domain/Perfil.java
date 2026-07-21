package br.gov.saude.sgpur.domain;

/** Perfil de acesso do usuario do sistema. */
public enum Perfil {
    ADMIN("Administrador"),
    OPERADOR("Operador"),
    AVALIADOR("Avaliador");

    private final String descricao;

    Perfil(String descricao) {
        this.descricao = descricao;
    }

    public String getDescricao() {
        return descricao;
    }

    /** Authority no formato esperado pelo Spring Security (ROLE_*). */
    public String getAuthority() {
        return "ROLE_" + name();
    }
}
