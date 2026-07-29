package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.domain.Perfil;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import br.gov.saude.sgpur.support.CamposDeFormulario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.beans.PropertyDescriptor;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Teste de INTEGRACAO (contexto Spring real + H2) de
 * {@code UsuarioService.atualizar}, contra a familia de bugs de <b>escrita
 * descartada em silencio</b> - o mesmo modo de falha que ja engoliu o
 * {@code email} deste service ("e facil esquecer de novo se reescrever esse
 * metodo", CLAUDE.md).
 *
 * <p>Le do banco depois do commit (nao do objeto em memoria que o service
 * acabou de mutar, como faz o teste com {@code @Mock UsuarioRepository}) e
 * confere campo a campo. O caso {@link #cadaCampoDoFormularioChegaAoBanco()}
 * deriva a lista de campos do proprio {@code usuarios/form.html}, entao um
 * campo novo no HTML sem a copia correspondente no service quebra o teste
 * sozinho.
 */
@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:sgpur-usuario-update;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.anexos.dir=./target/test-anexos-usuario-update"
})
class UsuarioAtualizacaoIntegrationTest {

    private static final String FORM = "templates/usuarios/form.html";

    /**
     * Campos do formulario que o service NAO copia do objeto vinculado, de
     * proposito (defesa contra mass assignment / senha em texto puro):
     * {@code senha} chega como parametro proprio e e codificada;
     * {@code membroId} e {@code equipeSolicitante} chegam como
     * {@code @RequestParam} e passam pela validacao por perfil.
     */
    private static final List<String> FORA_DO_OBJETO_VINCULADO =
        List.of("senha", "membroId", "equipeSolicitante");

    @Autowired
    private UsuarioService service;
    @Autowired
    private UsuarioRepository repo;
    @Autowired
    private MembroUrgenciaRenalRepository membroRepo;
    @Autowired
    private PasswordEncoder encoder;
    @Autowired
    private PlatformTransactionManager txManager;

    private TransactionTemplate tx;
    private Long id;
    private Long membroId;

    @BeforeEach
    void preparar() {
        tx = new TransactionTemplate(txManager);
        repo.findByUsername("alvo-original").ifPresent(repo::delete);
        repo.findByUsername("alvo-editado").ifPresent(repo::delete);
        repo.findByUsername("Z-username").ifPresent(repo::delete);
        membroId = membroRepo.save(
            new MembroUrgenciaRenal("HCPA", "Dra. Vinculada", "vinculada@example.com")).getId();

        Usuario u = new Usuario();
        u.setUsername("alvo-original");
        u.setNome("Nome Original");
        u.setEmail("original@example.com");
        u.setPerfil(Perfil.OPERADOR);
        u.setAtivo(true);
        id = service.criar(u, "SenhaOrig1!", null, null).getId();
    }

    /**
     * Padrao central contra a familia: altera TODOS os campos editaveis de uma
     * vez, com valores distintos, salva, RELE do banco e confere cada um.
     */
    @Test
    void atualizaTodosOsCamposEditaveisEReleDoBanco() {
        Usuario form = new Usuario();
        form.setUsername("alvo-editado");
        form.setNome("Nome Editado");
        form.setEmail("editado@example.com");
        form.setPerfil(Perfil.AVALIADOR);
        form.setAtivo(false);

        service.atualizar(id, form, "SenhaNova2!", membroId, null);

        tx.executeWithoutResult(s -> {
            Usuario doBanco = repo.findById(id).orElseThrow();
            assertThat(doBanco.getUsername()).isEqualTo("alvo-editado");
            assertThat(doBanco.getNome()).isEqualTo("Nome Editado");
            // O campo que ja foi esquecido uma vez neste mesmo metodo.
            assertThat(doBanco.getEmail()).isEqualTo("editado@example.com");
            assertThat(doBanco.getPerfil()).isEqualTo(Perfil.AVALIADOR);
            assertThat(doBanco.isAtivo()).isFalse();
            assertThat(doBanco.getMembro()).isNotNull();
            assertThat(doBanco.getMembro().getId()).isEqualTo(membroId);
            assertThat(doBanco.getEquipeSolicitante()).isNull();
            assertThat(encoder.matches("SenhaNova2!", doBanco.getSenha()))
                .as("nova senha gravada (codificada)").isTrue();
        });
    }

    /** A equipe do SOLICITANTE tambem precisa sobreviver a edicao. */
    @Test
    void atualizaEquipeDoSolicitanteELimpaMembro() {
        Usuario form = new Usuario();
        form.setUsername("alvo-original");
        form.setNome("Nome Solicitante");
        form.setEmail("solicitante@example.com");
        form.setPerfil(Perfil.SOLICITANTE);
        form.setAtivo(true);

        service.atualizar(id, form, null, null, "Hospital de Clinicas - Nefrologia");

        tx.executeWithoutResult(s -> {
            Usuario doBanco = repo.findById(id).orElseThrow();
            assertThat(doBanco.getPerfil()).isEqualTo(Perfil.SOLICITANTE);
            assertThat(doBanco.getEquipeSolicitante()).isEqualTo("Hospital de Clinicas - Nefrologia");
            assertThat(doBanco.getMembro()).isNull();
        });
    }

    /** Senha vazia mantem a senha atual (unico campo "opcional" do formulario). */
    @Test
    void senhaEmBrancoMantemASenhaAtual() {
        Usuario form = new Usuario();
        form.setUsername("alvo-original");
        form.setNome("Nome Editado");
        form.setEmail("editado@example.com");
        form.setPerfil(Perfil.OPERADOR);
        form.setAtivo(true);

        service.atualizar(id, form, "   ", null, null);

        Usuario doBanco = repo.findById(id).orElseThrow();
        assertThat(encoder.matches("SenhaOrig1!", doBanco.getSenha())).isTrue();
        assertThat(doBanco.getNome()).isEqualTo("Nome Editado");
    }

    /**
     * Guarda automatica: cada {@code th:field} de {@code usuarios/form.html}
     * precisa chegar ao banco. Campo novo no HTML sem copia no service = teste
     * vermelho, sem depender de ninguem lembrar de atualizar este arquivo.
     */
    @Test
    void cadaCampoDoFormularioChegaAoBanco() throws Exception {
        List<String> campos = CamposDeFormulario.thFields(FORM);
        assertThat(campos).contains("username", "nome", "email", "perfil", "ativo");

        for (String campo : campos) {
            if (FORA_DO_OBJETO_VINCULADO.contains(campo)) {
                continue;
            }
            Usuario atual = repo.findById(id).orElseThrow();
            PropertyDescriptor pd = new PropertyDescriptor(campo, Usuario.class);
            Object novoValor = CamposDeFormulario.valorDistinto(
                Usuario.class, campo, pd.getPropertyType(), pd.getReadMethod().invoke(atual));

            Usuario form = copiaEditavel(atual);
            pd.getWriteMethod().invoke(form, novoValor);

            service.atualizar(id, form, null, null, null);

            Object gravado = pd.getReadMethod().invoke(repo.findById(id).orElseThrow());
            assertThat(gravado)
                .as("campo '%s' aparece no formulario de usuario mas nao foi gravado por "
                    + "UsuarioService.atualizar - escrita descartada em silencio", campo)
                .isEqualTo(novoValor);
        }
    }

    private Usuario copiaEditavel(Usuario origem) {
        Usuario u = new Usuario();
        u.setUsername(origem.getUsername());
        u.setNome(origem.getNome());
        u.setEmail(origem.getEmail());
        u.setPerfil(origem.getPerfil());
        u.setAtivo(origem.isAtivo());
        return u;
    }
}
