package br.gov.saude.sgpur.e2e.pages;

import br.gov.saude.sgpur.e2e.Legenda;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;

import java.time.LocalDate;

/**
 * Page Object do Portal do Solicitante (/solicitante): envio de um novo
 * pedido de urgencia renal. O portal e publico so apos login com um usuario
 * de perfil {@code SOLICITANTE} (rota {@code /solicitante/**} exige
 * {@code ROLE_SOLICITANTE} em {@code SecurityConfig}) - a equipe/e-mail do
 * solicitante vem do proprio cadastro do usuario logado, nao e digitada
 * neste formulario.
 */
public class PortalSolicitantePage {

    private final Page page;

    public PortalSolicitantePage(Page page) {
        this.page = page;
    }

    private void narrar(String texto) {
        Legenda.mostrar(page, texto);
    }

    public PortalSolicitantePage abrirNova() {
        page.navigate("/solicitante/nova");
        narrar("Abrindo o formulario de nova solicitacao no Portal do Solicitante...");
        return this;
    }

    public PortalSolicitantePage preencher(String pacienteNome, String pacienteRgct,
                                            LocalDate dataSituacaoEspecial, String justificativaClinica) {
        narrar("Preenchendo o pedido de urgencia renal...");
        page.locator("input[name=pacienteNome]").fill(pacienteNome);
        page.locator("input[name=pacienteRgct]").fill(pacienteRgct);
        page.locator("input[name=dataSituacaoEspecial]").fill(dataSituacaoEspecial.toString());
        page.locator("textarea[name=justificativaClinica]").fill(justificativaClinica);
        return this;
    }

    /** Submete o pedido; o controller redireciona para a lista do solicitante (/solicitante). */
    public void enviar() {
        narrar("Enviando a solicitacao para a triagem da equipe de Urgencia Renal...");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Enviar solicitacao")).click();
        page.waitForURL("**/solicitante");
    }
}
