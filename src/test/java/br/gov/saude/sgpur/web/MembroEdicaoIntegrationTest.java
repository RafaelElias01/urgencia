package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Teste de INTEGRACAO (contexto Spring real + H2 real, sem mock de
 * repositorio) do formulario de edicao de membro avaliador.
 *
 * <p>Bug real de producao: {@code MembroUrgenciaRenal} tem {@code @Version
 * Long versao} (wrapper, nao primitivo) e o formulario envia o {@code id}
 * mas NAO a versao. A entidade chegava detached com {@code versao = null},
 * o {@code SimpleJpaRepository.save()} a considerava NOVA
 * ({@code JpaMetamodelEntityInformation.isNew()} com atributo de versao
 * nao-primitivo trata {@code null} como novo) e chamava {@code persist()}
 * em vez de {@code merge()} — resultado: "Detached entity with generated id
 * ... has an uninitialized version value 'null'", flash de erro sobre
 * "numero do processo" e redirect para {@code /processos}. O membro NUNCA
 * era alterado.</p>
 *
 * <p>NAO usar {@code @Transactional} aqui: com o mesmo persistence context
 * compartilhado entre teste e controller o bug NAO aparece (a entidade nao
 * chega detached). O teste precisa passar pelo commit de verdade.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:sgpur-membro-edicao;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class MembroEdicaoIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private MembroUrgenciaRenalRepository repo;

    @BeforeEach
    void limpar() {
        repo.deleteAll();
    }

    @Test
    @WithMockUser(username = "op", roles = "OPERADOR")
    void editarMembroAlteraORegistroExistenteSemCriarOutro() throws Exception {
        MembroUrgenciaRenal existente = repo.save(new MembroUrgenciaRenal("HCPA", "Nome Antigo", "antigo@x.com"));
        Long id = existente.getId();
        long totalAntes = repo.count();

        // Exatamente os campos que o form (membros/form.html) envia: id +
        // instituicao + nome + email + ativo + coordenador. Sem "versao".
        mvc.perform(post("/membros").with(csrf())
                .param("id", String.valueOf(id))
                .param("instituicao", "ISCMPA")
                .param("nome", "Nome Novo")
                .param("email", "novo@x.com")
                .param("ativo", "true"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/membros"));

        assertThat(repo.count()).as("nao pode ter criado um segundo registro").isEqualTo(totalAntes);

        MembroUrgenciaRenal relido = repo.findById(id).orElseThrow();
        assertThat(relido.getNome()).isEqualTo("Nome Novo");
        assertThat(relido.getInstituicao()).isEqualTo("ISCMPA");
        assertThat(relido.getEmail()).isEqualTo("novo@x.com");
        assertThat(relido.getVersao()).as("merge de verdade incrementa a versao").isGreaterThan(0L);
    }

    /**
     * A regra "so 1 coordenador CET-RS por vez" precisa continuar valendo
     * depois da correcao (o membro editado vira coordenador e o anterior e
     * desmarcado, tudo persistido de verdade).
     */
    @Test
    @WithMockUser(username = "op", roles = "OPERADOR")
    void editarMarcandoCoordenadorDesmarcaOAnteriorNoBanco() throws Exception {
        MembroUrgenciaRenal antigo = new MembroUrgenciaRenal("CET-RS", "Coordenador Antigo", null);
        antigo.setCoordenador(true);
        antigo = repo.save(antigo);
        MembroUrgenciaRenal novo = repo.save(new MembroUrgenciaRenal("HCPA", "Futuro Coordenador", null));

        mvc.perform(post("/membros").with(csrf())
                .param("id", String.valueOf(novo.getId()))
                .param("instituicao", "HCPA")
                .param("nome", "Futuro Coordenador")
                .param("ativo", "true")
                .param("coordenador", "true"))
            .andExpect(redirectedUrl("/membros"));

        assertThat(repo.findById(novo.getId()).orElseThrow().isCoordenador()).isTrue();
        assertThat(repo.findById(antigo.getId()).orElseThrow().isCoordenador()).isFalse();
        assertThat(repo.findByCoordenadorTrue()).hasSize(1);
    }

    /** Cadastro novo (sem id) continua criando um registro. */
    @Test
    @WithMockUser(username = "op", roles = "OPERADOR")
    void cadastrarMembroNovoContinuaFuncionando() throws Exception {
        mvc.perform(post("/membros").with(csrf())
                .param("instituicao", "HCPA")
                .param("nome", "Medico Novo")
                .param("ativo", "true"))
            .andExpect(redirectedUrl("/membros"));

        assertThat(repo.count()).isEqualTo(1);
        assertThat(repo.findAll().get(0).getNome()).isEqualTo("Medico Novo");
    }

    /* ----- status HTTP corretos (antes tudo virava 200 "Erro interno") ----- */

    @Test
    @WithMockUser(username = "op", roles = "OPERADOR")
    void editarIdInexistenteResponde404() throws Exception {
        mvc.perform(get("/membros/999999/editar"))
            .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "op", roles = "OPERADOR")
    void idNaoNumericoNaUrlResponde400() throws Exception {
        mvc.perform(get("/membros/abc/editar"))
            .andExpect(status().isBadRequest());
    }
}
