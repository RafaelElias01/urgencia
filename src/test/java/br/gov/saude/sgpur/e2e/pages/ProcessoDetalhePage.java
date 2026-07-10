package br.gov.saude.sgpur.e2e.pages;

import br.gov.saude.sgpur.e2e.Legenda;
import com.microsoft.playwright.Locator;
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

    // ===== Passo 1: Recebimento =====

    public ProcessoDetalhePage passo1_registrarRecebimento(FilePayload solicitacaoOriginal) {
        narrar("Passo 1/5 - Recebimento: anexando a solicitacao original recebida...");
        clicarPasso("pane-recebimento");
        page.locator("#pane-recebimento input[name=arquivo]").setInputFiles(solicitacaoOriginal);
        page.locator("#pane-recebimento button:has-text('Registrar recebimento')").click();
        page.waitForLoadState();
        return this;
    }

    // ===== Passo 2: Envio =====

    public ProcessoDetalhePage passo2_anexarDocumentoClinico(FilePayload documentoClinico) {
        narrar("Passo 2/5 - Envio: anexando o documento clinico anonimizado...");
        clicarPasso("pane-envio");
        page.locator("#pane-envio form[action*='documento-clinico'] input[name=arquivo]").setInputFiles(documentoClinico);
        page.locator("#pane-envio button:has-text('Anexar documento clinico')").click();
        page.waitForLoadState();
        return this;
    }

    public ProcessoDetalhePage passo2_anexarComprovanteEnvio(FilePayload comprovante) {
        narrar("Passo 2/5 - Envio: anexando o comprovante de envio aos avaliadores...");
        clicarPasso("pane-envio");
        page.locator("#pane-envio form[action*='comprovante-envio-avaliadores'] input[name=arquivo]").setInputFiles(comprovante);
        page.locator("#pane-envio form[action*='comprovante-envio-avaliadores'] button").click();
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

    // ===== Passo 3: Respostas (registra resultado + anexo de UM parecer, pela linha do medico) =====

    /** Registra o parecer do medico identificado pelo nome (texto da linha da tabela). */
    public ProcessoDetalhePage passo3_registrarParecer(String nomeMedico, String resultado, FilePayload respostaAvaliador) {
        narrar("Passo 3/5 - Respostas: registrando o parecer de " + nomeMedico + " (" + resultado + ")...");
        clicarPasso("pane-respostas");
        Locator linha = page.locator("#respostas tr", new Page.LocatorOptions().setHasText(nomeMedico));
        linha.locator("select[name=resultado]").selectOption(resultado);
        linha.locator("input[name=arquivo]").setInputFiles(respostaAvaliador);
        linha.locator("button.btn-anexar-resposta").click();
        page.waitForLoadState();
        return this;
    }

    // ===== Passo 4: Decisao =====

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

    public ProcessoDetalhePage passo5_anexarComprovanteSnt(FilePayload comprovanteSnt) {
        narrar("Passo 5/5 - Finalizacao: anexando o comprovante de insercao no SNT...");
        clicarPasso("pane-finalizacao");
        page.locator("#finalizacao form[action*='comprovante-snt'] input[name=arquivo]").setInputFiles(comprovanteSnt);
        page.locator("#finalizacao form[action*='comprovante-snt'] button").click();
        page.waitForLoadState();
        return this;
    }

    public ProcessoDetalhePage passo5_anexarComprovanteEnvioSolicitante(FilePayload comprovante) {
        narrar("Passo 5/5 - Finalizacao: anexando o comprovante de envio ao solicitante...");
        clicarPasso("pane-finalizacao");
        page.locator("#finalizacao form[action*='comprovante-envio-solicitante'] input[name=arquivo]").setInputFiles(comprovante);
        page.locator("#finalizacao form[action*='comprovante-envio-solicitante'] button").click();
        page.waitForLoadState();
        return this;
    }

    public ProcessoDetalhePage passo5_confirmarRespostaAoSolicitante() {
        narrar("Passo 5/5 - Finalizacao: confirmando o envio da resposta ao solicitante...");
        clicarPasso("pane-finalizacao");
        page.locator("#emailEnvSolicitante").check();
        page.locator("#finalizacao form[action*='resposta-solicitante'] button").click();
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
     * Confirma que o Relatorio Final (PDF) esta disponivel e e um PDF valido,
     * buscando o mesmo href do botao "Relatorio Final (PDF)" visivel na tela
     * via requisicao HTTP autenticada (reaproveita os cookies de sessao da
     * Page) - equivalente ao que o clique no link faria, mas sem depender de
     * o Chromium renderizar/baixar PDF de forma diferente em modo headless
     * vs. headed (o clique real chegou a disparar "Download is starting" em
     * headless, por o PDF vir com Content-Disposition: inline).
     */
    public byte[] abrirRelatorioFinal() {
        narrar("Abrindo o Relatorio Final (PDF) gerado pelo sistema...");
        String href = page.locator("a.btn:has-text('Relatorio Final (PDF)')").getAttribute("href");
        com.microsoft.playwright.APIResponse resp = page.request().get(href);
        if (!resp.ok()) {
            throw new IllegalStateException("Relatorio Final retornou HTTP " + resp.status() + " para " + href);
        }
        return resp.body();
    }

    public Page raw() {
        return page;
    }
}
