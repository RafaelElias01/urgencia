package br.gov.saude.sgpur.domain;

/**
 * Indica como o voto do medico avaliador foi registrado no sistema.
 *
 * <p>{@code AVALIADOR_SISTEMA} - o proprio medico se autentica no Portal do
 * Avaliador (/avaliador) e registra o voto diretamente. O registro autenticado
 * (usuario + dataHoraVoto + IP no log de auditoria) e a unica forma de voto
 * suportada.</p>
 */
public enum OrigemParecer {

    AVALIADOR_SISTEMA("Voto direto do avaliador autenticado");

    private final String descricao;

    OrigemParecer(String descricao) {
        this.descricao = descricao;
    }

    public String getDescricao() {
        return descricao;
    }
}
