package br.gov.saude.sgpur.e2e.pages;

import br.gov.saude.sgpur.e2e.Legenda;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;

/**
 * Page Object da fila de triagem do OPERADOR/ADMIN para os pedidos enviados
 * pelo Portal do Solicitante (/processos/solicitacoes-online). Desde a
 * mudanca de regra de negocio de 2026-07-27, e o UNICO jeito de um
 * {@code Processo} nascer - nao ha mais cadastro manual "do zero" (ver
 * {@link NovoProcessoPage}).
 */
public class SolicitacoesOnlineTriagemPage {

    private final Page page;

    public SolicitacoesOnlineTriagemPage(Page page) {
        this.page = page;
    }

    private void narrar(String texto) {
        Legenda.mostrar(page, texto);
    }

    public SolicitacoesOnlineTriagemPage abrir() {
        page.navigate("/processos/solicitacoes-online");
        narrar("Abrindo a fila de triagem de solicitacoes online...");
        return this;
    }

    /** Abre o detalhe da primeira solicitacao pendente da fila (a unica no cenario do teste). */
    public SolicitacoesOnlineTriagemPage abrirPrimeiraPendente() {
        narrar("Abrindo a solicitacao enviada pelo Portal do Solicitante...");
        page.locator("table tbody tr a:has-text('Ver')").first().click();
        page.waitForLoadState();
        return this;
    }

    /**
     * Clica "Revisar e converter" no detalhe da solicitacao (navega para
     * {@code /processos/novo?origemSolicitacaoOnlineId=...}, ja
     * pre-preenchido com os dados enviados pelo solicitante) e retorna o
     * Page Object do formulario de Novo processo para o operador continuar
     * o cadastro (revisar dados, escolher os 3 avaliadores, cadastrar).
     */
    public NovoProcessoPage revisarEConverter() {
        narrar("Convertendo a solicitacao em processo...");
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Revisar e converter")).click();
        page.waitForURL("**/processos/novo*");
        return new NovoProcessoPage(page);
    }
}
