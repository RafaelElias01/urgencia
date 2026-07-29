package br.gov.saude.sgpur.support;

import org.springframework.core.io.ClassPathResource;

import jakarta.persistence.Column;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Apoio dos testes que combatem a familia de bugs de <b>escrita descartada em
 * silencio</b>: metodos de atualizacao que copiam campo a campo de um objeto de
 * formulario para a entidade gerenciada e <i>esquecem</i> um campo - o usuario
 * salva, ve "atualizado com sucesso" e o dado e jogado fora sem erro nenhum.
 *
 * <p>Ja aconteceu 3 vezes no SAUR ({@code UsuarioService.atualizar} sem
 * {@code email}, {@code MembroController.salvar} com persist em vez de merge e
 * {@code ControleUrgenciaService.atualizar} sem {@code dataVencimento}).
 *
 * <p>A defesa e derivar a lista de campos <b>do proprio HTML</b> (e nao de uma
 * lista escrita a mao no teste): assim, no dia em que alguem adicionar um campo
 * novo ao formulario e esquecer de copia-lo no service, o teste quebra sozinho,
 * sem depender de ninguem lembrar de atualizar o teste tambem.
 */
public final class CamposDeFormulario {

    private static final Pattern TH_FIELD = Pattern.compile("th:field=\"\\*\\{([A-Za-z0-9_.]+)}\"");
    private static final Pattern INPUT_NAME = Pattern.compile("name=\"([A-Za-z0-9_]+)\"");

    private CamposDeFormulario() {
    }

    /** Le um template do classpath (ex.: {@code "templates/usuarios/form.html"}). */
    public static String ler(String caminhoNoClasspath) {
        try {
            return new String(new ClassPathResource(caminhoNoClasspath).getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Template nao encontrado no classpath: " + caminhoNoClasspath, e);
        }
    }

    /** Nomes de propriedade usados em {@code th:field="*{...}"} no template. */
    public static List<String> thFields(String caminhoNoClasspath) {
        return extrair(TH_FIELD, ler(caminhoNoClasspath));
    }

    /** Nomes de campo declarados como {@code name="..."} (inputs fora do th:field). */
    public static List<String> inputNames(String caminhoNoClasspath) {
        return extrair(INPUT_NAME, ler(caminhoNoClasspath));
    }

    private static List<String> extrair(Pattern p, String html) {
        List<String> nomes = new ArrayList<>();
        Matcher m = p.matcher(html);
        while (m.find()) {
            if (!nomes.contains(m.group(1))) {
                nomes.add(m.group(1));
            }
        }
        return nomes;
    }

    /**
     * Gera um valor novo, distinto do atual e <b>reconhecivel</b>, compativel
     * com o tipo da propriedade - respeitando o {@code length} da coluna JPA
     * (senao um campo curto como {@code abo}, de 3 caracteres, estouraria a
     * constraint e mascararia o que o teste quer medir).
     */
    public static Object valorDistinto(Class<?> entidade, String propriedade, Class<?> tipo, Object atual) {
        if (tipo == String.class) {
            // Campo anotado com @Email so aceita endereco bem-formado: o valor
            // generico "Z-<campo>" estoura ConstraintViolationException no
            // commit e o teste falharia por um detalhe do gerador, escondendo
            // o que ele deveria medir (campo do form que nao chega ao banco).
            if (temAnotacao(entidade, propriedade, jakarta.validation.constraints.Email.class)) {
                return "z." + propriedade.toLowerCase(java.util.Locale.ROOT) + "@example.com";
            }
            String candidato = "Z-" + propriedade;
            int limite = tamanhoDaColuna(entidade, propriedade);
            return candidato.substring(0, Math.min(candidato.length(), limite));
        }
        if (tipo == java.time.LocalDate.class) {
            java.time.LocalDate base = atual instanceof java.time.LocalDate d ? d : java.time.LocalDate.now();
            return base.plusDays(97);
        }
        if (tipo == java.time.LocalDateTime.class) {
            java.time.LocalDateTime base = atual instanceof java.time.LocalDateTime d ? d
                : java.time.LocalDateTime.now();
            return base.plusDays(97);
        }
        if (tipo == boolean.class || tipo == Boolean.class) {
            return !Boolean.TRUE.equals(atual);
        }
        if (tipo.isEnum()) {
            for (Object constante : tipo.getEnumConstants()) {
                if (!constante.equals(atual)) {
                    return constante;
                }
            }
        }
        throw new IllegalArgumentException("Sem regra de valor distinto para o tipo " + tipo
            + " (propriedade '" + propriedade + "' de " + entidade.getSimpleName() + "). "
            + "Adicione o tipo aqui ao inves de remover o campo do teste.");
    }

    private static int tamanhoDaColuna(Class<?> entidade, String propriedade) {
        try {
            Field f = entidade.getDeclaredField(propriedade);
            Column c = f.getAnnotation(Column.class);
            return c == null ? 255 : c.length();
        } catch (NoSuchFieldException e) {
            return 255;
        }
    }

    /** True se o campo da entidade tem a anotacao de validacao informada. */
    private static boolean temAnotacao(Class<?> entidade, String propriedade,
                                       Class<? extends java.lang.annotation.Annotation> anotacao) {
        try {
            return entidade.getDeclaredField(propriedade).isAnnotationPresent(anotacao);
        } catch (NoSuchFieldException e) {
            return false;
        }
    }
}
