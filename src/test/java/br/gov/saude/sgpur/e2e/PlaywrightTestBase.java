package br.gov.saude.sgpur.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Base para testes E2E de navegador (Playwright): sobe o SAUR real numa
 * porta aleatoria (H2 em memoria, perfil dev) e abre um browser Chromium
 * de verdade para simular um operador humano clicando na tela.
 *
 * <p>Roda so via ".\e2e.ps1" / "mvn verify -Pe2e" (maven-failsafe-plugin,
 * classes *IT.java) - nunca no "mvn test"/.\test.ps1 do dia a dia, que
 * continua rapido e sem dependencia de browser instalado.
 *
 * <p>Por padrao a janela do browser fica VISIVEL, com slowMo de 900ms entre
 * acoes (dá pra acompanhar o "bot" navegando). Para rodar sem janela (mais
 * rapido, ex. CI): ".\e2e.ps1 -Headless" (equivale a
 * "-Dsaur.e2e.headed=false").
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
public abstract class PlaywrightTestBase {

    protected static final Path SCREENSHOT_DIR = Paths.get("target", "e2e-screenshots");

    @LocalServerPort
    protected int port;

    private static Playwright playwright;
    private static Browser browser;
    protected BrowserContext context;
    protected Page page;
    private final List<BrowserContext> contextosExtras = new ArrayList<>();

    @BeforeAll
    static void launchBrowser() {
        playwright = Playwright.create();
        // Aceita tanto a system property (-Dsaur.e2e.headed=true, a forma
        // confiavel via Maven/Failsafe, que forka um processo separado e nem
        // sempre herda env vars do shell pai) quanto a env var SAUR_E2E_HEADED
        // (util fora do Maven, ex. rodando a classe direto na IDE).
        boolean headed = Boolean.parseBoolean(System.getProperty("saur.e2e.headed",
            System.getenv().getOrDefault("SAUR_E2E_HEADED", "true")));
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
            .setHeadless(!headed)
            .setSlowMo(headed ? 900 : 0));
    }

    @AfterAll
    static void closeBrowser() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }

    @BeforeEach
    void newContext() {
        context = browser.newContext(new Browser.NewContextOptions()
            .setBaseURL("http://localhost:" + port)
            .setViewportSize(1440, 1080));
        // Aceita automaticamente os confirm() nativos usados no wizard
        // (ex.: "Confirmar o registro do seu voto?"), como um humano clicando OK.
        context.onDialog(dialog -> dialog.accept());
        page = context.newPage();
    }

    @AfterEach
    void closeContext(org.junit.jupiter.api.TestInfo testInfo) {
        // fechar 2x o mesmo BrowserContext (ex.: teste ja fechou uma janela de
        // ator explicitamente) e uma operacao idempotente no Playwright - nao lanca.
        contextosExtras.forEach(BrowserContext::close);
        contextosExtras.clear();
        if (context != null) {
            context.close();
        }
    }

    /**
     * Tira um screenshot em target/e2e-screenshots, nomeado pelo teste.
     * Chame no catch/falha. Silencioso se a page/context/browser ja tiver
     * fechado (ex.: falha tardia apos o teste ja ter deixado o Chromium
     * instavel com varias janelas simultaneas) - a excecao original do
     * teste e o que importa, nao um erro secundario ao tentar capturar.
     */
    protected void screenshot(String nome) {
        try {
            page.screenshot(new Page.ScreenshotOptions()
                .setPath(SCREENSHOT_DIR.resolve(nome + ".png"))
                .setFullPage(true));
        } catch (RuntimeException e) {
            System.err.println("screenshot('" + nome + "') falhou (ignorado): " + e.getMessage());
        }
    }

    protected void login(String username, String senha) {
        login(page, username, senha);
    }

    /**
     * Loga um ator especifico numa Page ja aberta (ver {@link #novoAtor()}).
     * Usado para vários usuários coexistirem no mesmo teste (ex.: operador +
     * 3 avaliadores votando cada um na sua própria sessão), sem POST manual
     * a /logout.
     */
    protected Page login(Page alvo, String username, String senha) {
        alvo.navigate("/login");
        Legenda.mostrar(alvo, "Fazendo login como " + username + "...");
        alvo.locator("input[name=username]").fill(username);
        alvo.locator("input[name=password]").fill(senha);
        alvo.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Entrar")).click();
        alvo.waitForLoadState();
        return alvo;
    }

    /**
     * Abre uma nova "janela de navegador" com sessao/cookies PROPRIOS
     * (BrowserContext novo, nao uma aba do context principal) - necessario
     * porque o SAUR usa sessao de cookie unica: duas abas do MESMO contexto
     * compartilhariam login e um substituiria o outro. Cada ator (operador,
     * cada avaliador) usa seu proprio contexto para coexistir de verdade,
     * como se cada um estivesse no seu proprio computador.
     */
    protected Page novoAtor() {
        BrowserContext novo = browser.newContext(new Browser.NewContextOptions()
            .setBaseURL("http://localhost:" + port)
            .setViewportSize(1440, 1080));
        novo.onDialog(dialog -> dialog.accept());
        contextosExtras.add(novo);
        return novo.newPage();
    }

    /** Legenda fixa no topo da pagina anunciando a proxima acao do bot (ver {@link Legenda}). */
    protected void legenda(String texto) {
        Legenda.mostrar(page, texto);
    }

    /**
     * Rola suavemente ate o fim da pagina e depois volta ao topo - so
     * cosmetico (modo headed), para quem esta acompanhando visualmente
     * conseguir ver o conteudo abaixo da dobra antes do bot prosseguir.
     * Sem efeito/instantaneo em modo headless (nao ha nada pra ver).
     */
    protected void mostrarPaginaInteira(Page alvo) {
        try {
            alvo.evaluate("""
                async () => {
                    const passo = window.innerHeight * 0.85;
                    const altura = document.documentElement.scrollHeight;
                    for (let y = 0; y < altura; y += passo) {
                        window.scrollTo({top: y, behavior: 'smooth'});
                        await new Promise(r => setTimeout(r, 350));
                    }
                    window.scrollTo({top: 0, behavior: 'smooth'});
                }
                """);
        } catch (RuntimeException ignored) {
            // pagina em transicao - nao e critico, so um efeito visual.
        }
    }

    protected void mostrarPaginaInteira() {
        mostrarPaginaInteira(page);
    }
}
