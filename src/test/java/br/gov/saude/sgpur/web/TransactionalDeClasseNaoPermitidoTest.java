package br.gov.saude.sgpur.web;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Trava de arquitetura da familia de bug de rollback silencioso
 * (UnexpectedRollbackException por transacao compartilhada entre um metodo de
 * controller @Transactional e um service @Transactional chamado dentro de um
 * try/catch do metodo - ja custou o voto de um avaliador perdido, corrigida em
 * 2026-07-29 nos 6 controllers de web/).
 *
 * <p>Nenhuma classe de {@code web} pode ter {@code @Transactional} de NIVEL DE
 * CLASSE: a anotacao deve ficar sempre no METODO (onde o autor consegue
 * decidir, caso a caso, se ha um try/catch em volta de outro service
 * transacional exigindo NOT_SUPPORTED/TransactionTemplate em vez de uma
 * transacao simples). Uma anotacao de classe reintroduziria a familia inteira
 * de bug de uma vez, para todos os metodos daquela classe.
 *
 * <p>Anotacao de METODO continua permitida e e o padrao correto hoje (ver
 * {@code AvaliadorController}, {@code ProcessoDetalheController.detalhe},
 * etc.) - este teste so barra o nivel de classe.
 */
class TransactionalDeClasseNaoPermitidoTest {

    @Test
    void nenhumControllerTemTransactionalDeNivelDeClasse() throws Exception {
        List<Class<?>> comAnotacaoDeClasse = new ArrayList<>();
        for (Class<?> classe : controllersDoPacoteWeb()) {
            if (classe.isAnnotationPresent(Transactional.class)) {
                comAnotacaoDeClasse.add(classe);
            }
        }
        assertThat(comAnotacaoDeClasse)
            .as("Controllers com @Transactional de NIVEL DE CLASSE reintroduzem a familia de bug "
                + "de rollback silencioso (UnexpectedRollbackException, ver CLAUDE.md e o javadoc "
                + "de AvaliadorController.registrarVoto). Mova a anotacao para o(s) metodo(s) "
                + "especificos que realmente precisam de transacao, escolhendo caso a caso entre "
                + "@Transactional simples, readOnly, NOT_SUPPORTED ou TransactionTemplate.")
            .isEmpty();
    }

    /**
     * Carrega todas as classes top-level (nao anonimas/internas) do pacote web/.
     *
     * <p><b>Usa {@code getResources} (plural), nao {@code getResource}.</b> O
     * pacote {@code br.gov.saude.sgpur.web} existe em DUAS raizes do classpath
     * de teste: {@code target/classes} (os 23 controllers de producao) e
     * {@code target/test-classes} (as classes de teste deste mesmo pacote,
     * incluindo esta). {@code getResource} (singular) devolve so a PRIMEIRA
     * ocorrencia, e o Surefire costuma colocar {@code test-classes} antes de
     * {@code classes} no classpath - o teste teria escaneado so as classes de
     * TESTE, nunca os controllers de verdade, "passando" sempre (bug real
     * encontrado e corrigido durante a escrita deste teste: adicionar
     * {@code @Transactional} de proposito num controller nao derrubava o teste
     * com {@code getResource}). Com {@code getResources}, a uniao das duas
     * raizes inclui os controllers reais (o que importa) mais as classes de
     * teste do pacote (inofensivo: nenhuma tem {@code @Transactional} de classe).
     */
    private static List<Class<?>> controllersDoPacoteWeb()
            throws IOException, ClassNotFoundException, URISyntaxException {
        String pacote = "br.gov.saude.sgpur.web";
        String caminhoPacote = pacote.replace('.', '/');
        Enumeration<URL> raizes = Thread.currentThread().getContextClassLoader().getResources(caminhoPacote);
        List<Class<?>> classes = new ArrayList<>();
        int raizesEncontradas = 0;
        while (raizes.hasMoreElements()) {
            raizesEncontradas++;
            // Path.of(url.toURI()), NAO Path.of(url.getPath()): no Windows o
            // getPath() de uma URL file: comeca com barra antes da letra do
            // drive ("/C:/Users/...") e Path.of estoura InvalidPath ("Illegal
            // char <:> at index 2") - o teste erra no Windows e passa no CI
            // Linux, escondendo a trava de arquitetura justamente na maquina
            // onde o codigo e escrito. toURI() resolve o esquema file: nos dois
            // sistemas.
            Path raiz = Path.of(raizes.nextElement().toURI());
            try (Stream<Path> arquivos = Files.walk(raiz)) {
                for (Path arquivo : arquivos.filter(p -> p.toString().endsWith(".class")).toList()) {
                    String nomeArquivo = raiz.relativize(arquivo).toString()
                        .replace(File.separatorChar, '.')
                        .replace(".class", "");
                    if (nomeArquivo.contains("$")) {
                        continue; // classes internas/anonimas (records auxiliares, DTOs privados etc.)
                    }
                    classes.add(Class.forName(pacote + "." + nomeArquivo));
                }
            }
        }
        assertThat(raizesEncontradas)
            .as("Pacote %s nao encontrado em nenhuma raiz do classpath de teste - "
                + "o teste ficaria vazio e nunca falharia.", pacote)
            .isGreaterThan(0);
        // Sanity check especifico (nao so "lista nao vazia"): confirma que pelo
        // menos um controller de PRODUCAO de verdade foi carregado, provando que
        // a raiz de target/classes (nao so target/test-classes) foi escaneada.
        assertThat(classes).contains(AvaliadorController.class);
        return classes;
    }
}
