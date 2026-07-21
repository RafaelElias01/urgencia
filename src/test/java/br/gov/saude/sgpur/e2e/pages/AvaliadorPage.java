package br.gov.saude.sgpur.e2e.pages;

import br.gov.saude.sgpur.e2e.Legenda;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;

/**
 * Page Object do Portal do Avaliador (/avaliador): o medico se autentica e
 * vota diretamente no processo, sem passar pelo operador. Reflete o fluxo
 * real da Fase 1 do portal (origem AVALIADOR_SISTEMA).
 */
public class AvaliadorPage {

    private final Page page;

    public AvaliadorPage(Page page) {
        this.page = page;
    }

    private void narrar(String texto) {
        Legenda.mostrar(page, texto);
    }

    /** Abre a tela de voto para um processo especifico e vai direto ao formulario. */
    public AvaliadorPage abrirVotacao(Long processoId) {
        page.navigate("/avaliador/" + processoId);
        narrar("Avaliador autenticado revisando o processo para emitir o parecer...");
        return this;
    }

    /**
     * Locator do &lt;iframe&gt; que embute o PDF anonimizado diretamente na tela
     * de votacao (visualizacao sem download, ver votar.html). Usado pelo teste
     * para confirmar que o material carrega inline em vez de exigir um download.
     */
    public Locator materialInline() {
        return page.locator("iframe[title='Visualizacao do processo anonimizado']");
    }

    /**
     * Preenche o resultado (FAVORAVEL / NAO_FAVORAVEL / SOLICITA_INFORMACAO),
     * a justificativa opcional, e confirma o voto. O confirm() nativo do
     * browser e aceito automaticamente pelo listener de dialog da base.
     */
    public AvaliadorPage votar(String resultado, String justificativa) {
        narrar("Registrando o voto: " + resultado + "...");
        page.locator("#resultado_" + resultado).check();
        if (justificativa != null && !justificativa.isBlank()) {
            page.locator("textarea[name=justificativa]").fill(justificativa);
        }
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Registrar meu voto")).click();
        page.waitForLoadState();
        return this;
    }
}
