package br.gov.saude.sgpur.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Impede que um icone INEXISTENTE do Bootstrap Icons entre nos templates.
 *
 * <p>Motivacao (bug real de 2026-07-29): o passo "Decisao" da timeline usava
 * {@code <i class="bi bi-scale">}, mas {@code bi-scale} NAO existe no
 * Bootstrap Icons. O {@code <i>} renderizava sem glifo nenhum e o usuario via
 * so o circulo azul solido do {@code .timeline-dot}, sem simbolo. Nenhum dos
 * 500+ testes pegou isso, porque um nome de classe CSS errado nao quebra nada
 * no servidor - so some visualmente no navegador.
 *
 * <p>Este teste fecha essa classe inteira de bug: cruza TODA classe
 * {@code bi-*} escrita nos templates Thymeleaf e nos JS do projeto contra a
 * lista de icones realmente declarados no CSS do webjar {@code bootstrap-icons}
 * que esta no classpath. A versao NAO e hardcoded - o CSS e localizado por
 * wildcard, entao subir a versao no {@code pom.xml} nao exige mexer aqui.
 */
class IconesBootstrapTest {

    /** Regras de icone no CSS do webjar: {@code .bi-alarm-fill::before { content: "\f101"; }}. */
    private static final Pattern REGRA_ICONE_CSS = Pattern.compile("^\\.(bi-[a-z0-9-]+)::before", Pattern.MULTILINE);

    /**
     * Uso de classe de icone nos fontes. O lookbehind evita casar o sufixo de
     * palavras maiores (ex.: {@code combi-nado}, {@code x-bi-y}); a classe base
     * {@code bi} sozinha nao casa porque exigimos ao menos 1 caractere depois
     * do hifen - e ela nao e um icone, so o seletor da familia da fonte.
     */
    private static final Pattern USO_ICONE = Pattern.compile("(?<![A-Za-z0-9_-])bi-[a-z0-9-]+");

    /**
     * Classes proprias do projeto que casam com o padrao {@code bi-*} mas NAO
     * sao icones do Bootstrap Icons (definidas no {@code app.css} ou em algum
     * JS). Hoje esta vazia: verificado que nenhuma classe autoral do SAUR usa
     * esse prefixo. Se algum dia precisar de uma, adicione aqui com um
     * comentario dizendo onde ela e definida - nunca afrouxe o regex acima.
     */
    private static final Set<String> ALLOWLIST_CLASSES_DO_PROJETO = Set.of();

    private static final Path DIR_TEMPLATES = Path.of("src", "main", "resources", "templates");
    private static final Path DIR_JS = Path.of("src", "main", "resources", "static", "js");

    @Test
    @DisplayName("todo icone bi-* usado em template/JS existe no webjar do Bootstrap Icons")
    void nenhumIconeInexistente() throws IOException {
        Set<String> validos = iconesValidosDoWebjar();
        assertThat(validos)
                .as("o CSS do bootstrap-icons deveria declarar centenas de icones - "
                        + "se veio quase vazio, o parser do CSS e que esta errado, nao os templates")
                .hasSizeGreaterThan(500)
                .contains("bi-check", "bi-x-lg", "bi-hourglass-split");

        // classe invalida -> "arquivo:linha" onde apareceu
        Map<String, Set<String>> invalidos = new TreeMap<>();
        for (Path arquivo : arquivosParaVarrer()) {
            List<String> linhas = Files.readAllLines(arquivo, StandardCharsets.UTF_8);
            for (int i = 0; i < linhas.size(); i++) {
                Matcher m = USO_ICONE.matcher(linhas.get(i));
                while (m.find()) {
                    String classe = m.group();
                    if (validos.contains(classe) || ALLOWLIST_CLASSES_DO_PROJETO.contains(classe)) {
                        continue;
                    }
                    invalidos.computeIfAbsent(classe, k -> new LinkedHashSet<>())
                            .add(arquivo + ":" + (i + 1));
                }
            }
        }

        assertThat(invalidos)
                .as("Classes bi-* que NAO existem no Bootstrap Icons (renderizam um icone "
                        + "invisivel no navegador). Confira o nome correto em "
                        + "https://icons.getbootstrap.com/ .%nOcorrencias:%n%s",
                        formatar(invalidos))
                .isEmpty();
    }

    /** Nomes de icone declarados no CSS do webjar que esta no classpath. */
    private Set<String> iconesValidosDoWebjar() throws IOException {
        Resource[] achados = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:/META-INF/resources/webjars/bootstrap-icons/*/font/bootstrap-icons.css");
        assertThat(achados)
                .as("CSS do webjar bootstrap-icons nao encontrado no classpath - "
                        + "a dependencia foi removida/renomeada no pom.xml?")
                .isNotEmpty();

        Set<String> icones = new LinkedHashSet<>();
        for (Resource r : achados) {
            String css = new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            Matcher m = REGRA_ICONE_CSS.matcher(css);
            while (m.find()) {
                icones.add(m.group(1));
            }
        }
        return icones;
    }

    private List<Path> arquivosParaVarrer() throws IOException {
        assertThat(DIR_TEMPLATES).as("diretorio de templates nao encontrado a partir de %s",
                Path.of("").toAbsolutePath()).exists();
        try (Stream<Path> templates = Files.walk(DIR_TEMPLATES);
             Stream<Path> js = Files.exists(DIR_JS) ? Files.walk(DIR_JS) : Stream.empty()) {
            List<Path> arquivos = Stream.concat(
                            templates.filter(p -> p.toString().endsWith(".html")),
                            js.filter(p -> p.toString().endsWith(".js")))
                    .sorted()
                    .toList();
            assertThat(arquivos).as("nenhum template/JS encontrado para varrer").isNotEmpty();
            return arquivos;
        } catch (UncheckedIOException e) {
            throw new IOException(e);
        }
    }

    private String formatar(Map<String, Set<String>> invalidos) {
        Map<String, Set<String>> ordenado = new LinkedHashMap<>(invalidos);
        StringBuilder sb = new StringBuilder();
        ordenado.forEach((classe, locais) ->
                sb.append("  - ").append(classe).append(" em ").append(String.join(", ", locais)).append('\n'));
        return sb.toString();
    }
}
