package br.gov.saude.sgpur.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Garante que o CSS/JS do sistema continue CACHEAVEL pelo navegador.
 *
 * <p>Bug real (2026-07-29): o {@code layout.html} montava o link do
 * {@code app.css} com {@code (v=${#dates.format(#dates.createNow(),...)})},
 * gerando uma URL diferente A CADA REQUEST. Cada tela aberta rebaixava o CSS
 * inteiro - numa conexao ruim isso vira um flash de pagina sem estilo a cada
 * clique. O cache-buster era desnecessario: a resource chain do Spring
 * (spring.web.resources.chain.strategy.content) ja poe o HASH DO CONTEUDO no
 * nome do arquivo, entao a URL muda sozinha quando (e so quando) o CSS muda.
 *
 * <p>Este teste trava as duas metades da correcao:
 * <ol>
 *   <li>a URL do CSS e ESTAVEL entre requests e traz o hash no nome;</li>
 *   <li>a resposta do proprio arquivo e cacheavel (sem o
 *       {@code Cache-Control: no-store} que o Spring Security aplica por
 *       padrao) - sem isso, remover o {@code ?v} nao adiantaria nada.</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:sgpur-cache;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
class RecursosEstaticosCacheTest {

    /** Ex.: /css/app-ac48bdbb78905ee7aa9e46e66b555b01.css */
    private static final Pattern LINK_APP_CSS =
        Pattern.compile("href=\"(/css/app[^\"]*\\.css[^\"]*)\"");

    @Autowired
    private MockMvc mvc;

    private String urlDoAppCss() throws Exception {
        String html = mvc.perform(get("/login"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        Matcher m = LINK_APP_CSS.matcher(html);
        assertThat(m.find())
            .as("o layout deve linkar o app.css na tela de login")
            .isTrue();
        return m.group(1);
    }

    @Test
    void urlDoAppCssEhEstavelEntreRequestsETrazHashDoConteudo() throws Exception {
        String primeira = urlDoAppCss();
        String segunda = urlDoAppCss();

        assertThat(primeira)
            .as("URL do CSS nao pode mudar a cada request (cache-buster por timestamp)")
            .isEqualTo(segunda)
            .as("sem query string: a invalidacao vem do hash no nome do arquivo")
            .doesNotContain("?")
            .matches("/css/app-[0-9a-f]{32}\\.css");
    }

    @Test
    void respostaDoAppCssEhCacheavelPeloNavegador() throws Exception {
        String cacheControl = mvc.perform(get(urlDoAppCss()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getHeader("Cache-Control");

        assertThat(cacheControl)
            .as("com no-store o navegador rebaixa o CSS a cada tela, mesmo sem o ?v")
            .isNotNull()
            .doesNotContain("no-store")
            .contains("max-age=");
    }
}
