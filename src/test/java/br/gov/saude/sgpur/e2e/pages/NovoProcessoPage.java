package br.gov.saude.sgpur.e2e.pages;

import br.gov.saude.sgpur.e2e.Legenda;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;

import java.time.LocalDate;
import java.util.List;

/**
 * Page Object do formulario "Novo processo" (/processos/novo).
 *
 * <p>Desde a mudanca de regra de negocio de 2026-07-27, nao existe mais
 * cadastro manual "do zero": {@code GET/POST /processos} EXIGE
 * {@code origemSolicitacaoOnlineId} (senao redireciona para a fila de
 * triagem). Na pratica um operador chega neste formulario clicando em
 * "Revisar e converter" no detalhe de uma solicitacao do Portal do
 * Solicitante (ver {@link SolicitacoesOnlineTriagemPage#revisarEConverter()}),
 * que ja navega para ca com o parametro certo - use {@link #abrir(Long)} so
 * quando precisar pular direto para ca sabendo o id da solicitacao.
 */
public class NovoProcessoPage {

    private final Page page;

    public NovoProcessoPage(Page page) {
        this.page = page;
    }

    private void narrar(String texto) {
        Legenda.mostrar(page, texto);
    }

    /** Navega direto para o formulario, ja associado a solicitacao de origem informada. */
    public NovoProcessoPage abrir(Long origemSolicitacaoOnlineId) {
        page.navigate("/processos/novo?origemSolicitacaoOnlineId=" + origemSolicitacaoOnlineId);
        narrar("Abrindo o formulario de novo processo (pre-preenchido pela solicitacao online)...");
        return this;
    }

    public NovoProcessoPage preencher(String numero, LocalDate dataSituacaoEspecial,
                                       String pacienteNome, String pacienteRgct,
                                       String solicitanteEquipe, String solicitanteEmail) {
        narrar("Preenchendo os dados do paciente e da equipe solicitante...");
        var numeroInput = page.locator("input[name=numero]");
        if (numeroInput.count() > 0 && numeroInput.isEnabled()) {
            numeroInput.fill(numero);
        }
        page.locator("input[name=dataSituacaoEspecial]").fill(dataSituacaoEspecial.toString());
        page.locator("input[name=pacienteNome]").fill(pacienteNome);
        page.locator("input[name=pacienteRgct]").fill(pacienteRgct);
        page.locator("input[name=solicitanteEquipe]").fill(solicitanteEquipe);
        page.locator("input[name=solicitanteEmail]").fill(solicitanteEmail);
        return this;
    }

    /** Marca exatamente os 3 medicos avaliadores pelos ids informados. */
    public NovoProcessoPage selecionarMedicos(List<Long> medicoIds) {
        narrar("Selecionando os 3 medicos avaliadores...");
        for (Long id : medicoIds) {
            page.locator("#med" + id).check();
        }
        return this;
    }

    /** Submete o form e retorna a Page de Detalhe do processo criado (redirect). */
    public ProcessoDetalhePage cadastrar() {
        narrar("Cadastrando o processo...");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Cadastrar")).click();
        page.waitForURL("**/processos/*");
        return new ProcessoDetalhePage(page);
    }
}
