package br.gov.saude.sgpur.service;

/**
 * Representa um dos 5 passos fixos do wizard horizontal na tela de detalhe
 * do processo. Agrupa as {@link EtapaFluxo} correspondentes para que o
 * wizard e a timeline vertical (card "Progresso") sempre concordem sobre o
 * que esta concluido, atual ou bloqueado.
 *
 * @param numero  posicao do passo (1 a 5)
 * @param titulo  rotulo curto exibido no wizard
 * @param paneId  id do elemento (tab-pane) associado no template
 * @param estado  CONCLUIDA, ATUAL ou BLOQUEADA
 * @param tooltip texto exibido no title/tooltip do passo
 */
public record PassoWizard(int numero, String titulo, String paneId, Estado estado, String tooltip) {

    public enum Estado {
        CONCLUIDA, ATUAL, BLOQUEADA
    }
}
