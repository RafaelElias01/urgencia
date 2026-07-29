package br.gov.saude.sgpur.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Impede que um botao/link SO COM ICONE entre nos templates sem rotulo
 * acessivel.
 *
 * <p>Motivacao (achado de 2026-07-29): o botao de "limpar filtro" de
 * {@code processos/lista.html} era {@code <a ...><i class="bi bi-x-lg"></i></a>}
 * sem {@code title} nem {@code aria-label} - um leitor de tela anuncia so
 * "link", sem nome. O botao irmao de {@code arquivo/lista.html} ja tinha os
 * dois atributos; era o unico caso restante no projeto.
 *
 * <p>Nenhum teste de MockMvc pega isso: a pagina responde 200 e o atributo que
 * falta nao quebra nada no servidor - so some para quem usa leitor de tela.
 */
class AcessibilidadeBotaoIconeTest {

    private static final Path TEMPLATES = Path.of("src/main/resources/templates");

    /** {@code <a ...>...</a>} / {@code <button ...>...</button>} (sem aninhamento do mesmo tipo). */
    private static final Pattern ELEMENTO_CLICAVEL =
        Pattern.compile("<(a|button)\\b([^>]*)>(.*?)</\\1>", Pattern.DOTALL);

    private static final Pattern TAG = Pattern.compile("<[^>]+>");

    /** Atributos que dao nome acessivel ao elemento (qualquer um basta). */
    private static final List<String> ROTULOS =
        List.of("aria-label", "aria-labelledby", "title=", "th:title", "th:text", "th:utext");

    @Test
    void botaoOuLinkSoComIconeTemRotuloAcessivel() throws IOException {
        List<String> semRotulo = new ArrayList<>();

        try (Stream<Path> arquivos = Files.walk(TEMPLATES)) {
            arquivos.filter(p -> p.toString().endsWith(".html")).forEach(p -> {
                String html = ler(p);
                Matcher m = ELEMENTO_CLICAVEL.matcher(html);
                while (m.find()) {
                    String atributos = m.group(2);
                    String conteudo = m.group(3);

                    // Tem texto visivel literal? Entao ja tem nome acessivel.
                    if (!TAG.matcher(conteudo).replaceAll("").trim().isEmpty()) continue;
                    // So interessa o caso "conteudo e apenas um icone/svg".
                    if (!conteudo.contains("<i ") && !conteudo.contains("<svg")) continue;
                    if (ROTULOS.stream().anyMatch(atributos::contains)) continue;

                    int linha = (int) html.substring(0, m.start()).chars()
                        .filter(c -> c == '\n').count() + 1;
                    semRotulo.add(p + ":" + linha + " -> <" + m.group(1) + " "
                        + atributos.trim().replaceAll("\\s+", " ") + ">");
                }
            });
        }

        assertThat(semRotulo)
            .as("botao/link apenas com icone precisa de title e/ou aria-label "
                + "(leitor de tela anuncia so 'link'/'botao' sem nome)")
            .isEmpty();
    }

    private static String ler(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
