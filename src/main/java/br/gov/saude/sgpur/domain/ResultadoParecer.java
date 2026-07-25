package br.gov.saude.sgpur.domain;

/**
 * Resultado do parecer de um membro da Urgencia Renal sobre o processo.
 * Valores extraidos da aba "Validacao" da planilha original.
 */
public enum ResultadoParecer {
    FAVORAVEL("Favoravel"),
    NAO_FAVORAVEL("Nao favoravel"),
    SOLICITA_INFORMACAO("Solicita informacao"),
    SEM_RESPOSTA("Sem resposta");

    private final String descricao;

    ResultadoParecer(String descricao) {
        this.descricao = descricao;
    }

    public String getDescricao() {
        return descricao;
    }

    /** SEM_RESPOSTA existe só para relatorios/estado interno - nao e um voto que um avaliador possa submeter. */
    public boolean isVotoValido() {
        return this != SEM_RESPOSTA;
    }
}
