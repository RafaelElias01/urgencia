package br.gov.saude.sgpur.service;

/**
 * Representa uma etapa do fluxo do processo para exibicao em tempo real.
 *
 * @param titulo   nome da etapa
 * @param icone    classe do bootstrap-icon (sem o "bi-")
 * @param estado   CONCLUIDA, ATUAL ou PENDENTE
 * @param detalhe  mensagem do que falta ou do que ja foi feito
 */
public record EtapaFluxo(String titulo, String icone, Estado estado, String detalhe) {

    public enum Estado {
        CONCLUIDA, ATUAL, PENDENTE
    }

    public boolean isConcluida() {
        return estado == Estado.CONCLUIDA;
    }

    public boolean isAtual() {
        return estado == Estado.ATUAL;
    }
}
