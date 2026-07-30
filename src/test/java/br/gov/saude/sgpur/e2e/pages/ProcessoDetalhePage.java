package br.gov.saude.sgpur.e2e.pages;

import br.gov.saude.sgpur.e2e.Legenda;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.FilePayload;

/**
 * Page Object da tela de detalhe do processo (wizard de 5 passos). Cada
 * metodo corresponde a uma acao que um operador humano realizaria clicando
 * na tela - nao chama nenhum endpoint diretamente.
 */
public class ProcessoDetalhePage {

    private final Page page;

    public ProcessoDetalhePage(Page page) {
        this.page = page;
    }

    private void narrar(String texto) {
        Legenda.mostrar(page, texto);
    }

    private void clicarPasso(String paneId) {
        page.locator(".wizard-step[href='#" + paneId + "']").click();
    }

    // ===== Passo 1: Recebimento (SEMPRE automatico desde 2026-07-27 - todo
    //       processo nasce de uma SolicitacaoOnline convertida pelo operador,
    //       sem nenhuma acao manual de recebimento; ver passoConcluido(1)) =====

    // ===== Passo 2: Envio =====

    public ProcessoDetalhePage passo2_anexarDocumentoClinico(FilePayload documentoClinico) {
        narrar("Passo 2/5 - Envio: anexando o documento clinico anonimizado...");
        clicarPasso("pane-envio");
        page.locator("#pane-envio form[action*='documento-clinico'] input[name=arquivo]").setInputFiles(documentoClinico);
        page.locator("#pane-envio button:has-text('Anexar documento clinico')").click();
        page.waitForLoadState();
        return this;
    }

    public ProcessoDetalhePage passo2_registrarEnvio() {
        narrar("Passo 2/5 - Envio: registrando o envio aos 3 avaliadores...");
        clicarPasso("pane-envio");
        page.locator("#pane-envio button:has-text('Registrar envio')").click();
        page.waitForLoadState();
        return this;
    }

    // ===== Passo 3: Respostas (registradas pelos proprios avaliadores no Portal
    //       do Avaliador - ver AvaliadorPage - nao ha mais acao do operador aqui) =====

    // ===== Passo 4: Decisao =====
    //
    // O sistema decide sozinho (ProcessoService.tentarDecisaoAutomatica,
    // chamado por AvaliadorController logo apos cada voto) assim que a
    // maioria simples se forma (2 favoraveis defere, 2 desfavoraveis
    // indefere; ou o coordenador CET-RS vota favoravel sozinho) - inclusive
    // gerando o oficio de indeferimento automaticamente. O formulario manual
    // abaixo (`#decisaoSelect`) SO aparece quando o processo AINDA NAO esta
    // finalizado (th:unless="${processo.status.finalizado}" no template) -
    // usado para Cancelado (nunca automatico) ou para redecidir apos uma
    // reabertura pelo ADMIN. No cenario feliz de 2/3 votos formando maioria,
    // nunca chame este metodo - o processo ja chega finalizado sozinho.
    public ProcessoDetalhePage passo4_decidir(String decisao) {
        return passo4_decidir(decisao, null);
    }

    public ProcessoDetalhePage passo4_decidir(String decisao, String motivoIndeferimento) {
        narrar("Passo 4/5 - Decisao: registrando a decisao final (" + decisao + ")...");
        clicarPasso("pane-decisao");
        page.locator("#decisaoSelect").selectOption(decisao);
        if (motivoIndeferimento != null) {
            page.locator("#motivoIndeferimentoInput").fill(motivoIndeferimento);
        }
        page.locator("#decisao button:has-text('Registrar decisao')").click();
        page.waitForLoadState();
        return this;
    }

    // ===== Passo 5: Finalizacao =====

    /** Anexa/substitui o comprovante SNT (obrigatorio so quando DEFERIDO - nao e gerado pelo sistema). */
    public ProcessoDetalhePage passo5_anexarComprovanteSnt(FilePayload comprovanteSnt) {
        narrar("Passo 5/5 - Finalizacao: anexando o comprovante de insercao no SNT...");
        clicarPasso("pane-finalizacao");
        page.locator("#finalizacao form[action*='comprovante-snt'] input[name=arquivo]").setInputFiles(comprovanteSnt);
        page.locator("#finalizacao form[action*='comprovante-snt'] button").click();
        page.waitForLoadState();
        return this;
    }

    /**
     * Clica no botao unico "Enviar Resposta ao Solicitante"
     * (POST /processos/{id}/finalizar): dispara o e-mail com o template
     * certo (deferido/indeferido) + o anexo correspondente, tudo automatico
     * - nao ha mais checkbox de confirmacao nem upload manual de
     * "comprovante de envio" nesta etapa. So habilitado quando o documento
     * obrigatorio da decisao (Oficio de Indeferimento ou Comprovante SNT) ja
     * estiver anexado.
     */
    public ProcessoDetalhePage passo5_confirmarRespostaAoSolicitante() {
        narrar("Passo 5/5 - Finalizacao: enviando a resposta final ao solicitante...");
        clicarPasso("pane-finalizacao");
        page.locator("#finalizacao form[action*='/finalizar'] button").click();
        page.waitForLoadState();
        return this;
    }

    // ===== Asserts / leitura de estado =====

    /** true se o passo (1 a 5) esta marcado como concluido (classe .concluida) na barra do wizard. */
    public boolean passoConcluido(int numero) {
        return page.locator(".wizard-step:nth-child(" + numero + ")")
            .getAttribute("class").contains("concluida");
    }

    public String statusAtual() {
        return page.locator("[data-testid=status-processo]").count() > 0
            ? page.locator("[data-testid=status-processo]").innerText()
            : page.locator("h1").innerText();
    }

    /**
     * Clica de verdade no botao "Relatorio Final (PDF)" (target=_blank) e
     * espera a nova aba abrir, para quem esta acompanhando em modo headed
     * VER o PDF sendo renderizado na tela pelo visualizador nativo do
     * Chrome - e o comportamento real de um operador clicando no botao.
     *
     * <p>So funciona de forma visivel em modo HEADED: o Chromium headless
     * nao tem visualizador de PDF embutido e trata a mesma resposta como
     * DOWNLOAD em vez de navegar para uma pagina - {@code waitForLoadState()}
     * nunca dispara o evento "load" nesse caso e trava ate o timeout (60s).
     * Por isso so esperamos o load state quando {@code headed}; em headless
     * basta confirmar que a aba abriu (a URL ja fica disponivel antes do
     * load terminar).
     */
    public Page abrirRelatorioFinal(boolean headed) {
        narrar("Abrindo o Relatorio Final (PDF) gerado pelo sistema...");
        Page abaRelatorio = page.context().waitForPage(() ->
            page.locator("a.btn:has-text('Relatorio Final (PDF)')").click());
        if (headed) {
            abaRelatorio.waitForLoadState();
        }
        return abaRelatorio;
    }

    public Page raw() {
        return page;
    }
}
