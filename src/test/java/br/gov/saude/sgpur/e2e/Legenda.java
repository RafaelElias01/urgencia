package br.gov.saude.sgpur.e2e;

import com.microsoft.playwright.Page;

/**
 * Injeta uma legenda fixa (estilo "closed caption") no topo da pagina
 * durante os testes E2E, anunciando o que o "bot" esta prestes a fazer.
 * Puramente cosmetico, para acompanhar visualmente em modo headed - nao
 * afeta nenhuma logica de teste. Silencioso se a chamada falhar (pagina
 * em transicao de navegacao).
 */
public final class Legenda {

    private static final String JS = """
        (msg) => {
            let el = document.getElementById('saur-e2e-legenda');
            if (!el) {
                el = document.createElement('div');
                el.id = 'saur-e2e-legenda';
                el.style.cssText = 'position:fixed;top:0;left:0;right:0;z-index:2147483647;'
                    + 'background:#111827;color:#facc15;font:600 15px/1.4 monospace;'
                    + 'padding:10px 16px;text-align:center;box-shadow:0 2px 8px rgba(0,0,0,.4);'
                    + 'pointer-events:none;';
                document.documentElement.appendChild(el);
            }
            el.textContent = '\\uD83E\\uDD16 ' + msg;
        }
        """;

    private Legenda() {
    }

    public static void mostrar(Page page, String texto) {
        if (page == null) return;
        try {
            page.evaluate(JS, texto);
        } catch (RuntimeException ignored) {
            // pagina em transicao (navigate em andamento) - a proxima chamada reaplica.
        }
    }
}
