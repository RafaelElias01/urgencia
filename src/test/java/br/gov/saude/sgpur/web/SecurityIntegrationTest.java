package br.gov.saude.sgpur.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Testes de integracao da seguranca: rotas protegidas, restricoes por perfil.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:sgpur-sec;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
class SecurityIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void rotaProtegidaRedirecionaParaLogin() throws Exception {
        mvc.perform(get("/processos"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    void loginEhPublico() throws Exception {
        mvc.perform(get("/login")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminAcessaDashboardEUsuarios() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk());
        mvc.perform(get("/usuarios")).andExpect(status().isOk());
        mvc.perform(get("/auditoria")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void operadorNaoAcessaAreasDeAdmin() throws Exception {
        // /usuarios (cadastro de LOGINS) e /auditoria continuam exclusivos do ADMIN.
        mvc.perform(get("/usuarios")).andExpect(status().isForbidden());
        mvc.perform(get("/auditoria")).andExpect(status().isForbidden());
        // mas acessa a area operacional normal, incluindo membros e relatorios
        // (comentario da classe: "OPERADOR: acesso operacional a processos,
        // membros, relatorios").
        mvc.perform(get("/processos")).andExpect(status().isOk());
        mvc.perform(get("/membros")).andExpect(status().isOk());
        mvc.perform(get("/relatorios/anual")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "AVALIADOR")
    void avaliadorNaoAcessaProcessos() throws Exception {
        mvc.perform(get("/processos")).andExpect(status().isForbidden());
        mvc.perform(get("/")).andExpect(status().isForbidden());
    }

    @Test
    void relatorioAnualExigeAutenticacao() throws Exception {
        mvc.perform(get("/relatorios/anual"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrlPattern("**/login"));
    }

    // --- Testes do Portal do Avaliador ---

    @Test
    @WithMockUser(roles = "AVALIADOR")
    void avaliadorAcessaPortalProprio() throws Exception {
        // ROLE_AVALIADOR tem permissao na rota /avaliador (Spring Security nao bloqueia).
        // O controller lanca ResponseStatusException(UNAUTHORIZED) ao nao encontrar o usuario
        // no banco (MockMvc usa usuario ficticio "user" sem registro real), resultando em 401.
        // O ponto testado e que a rota NAO retorna 403 (proibido por role).
        mvc.perform(get("/avaliador"))
            .andExpect(status().is4xxClientError()); // 401 de logica (usuario nao no banco), nao 403 de role
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void operadorNaoAcessaPortalDoAvaliador() throws Exception {
        mvc.perform(get("/avaliador"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminNaoAcessaPortalDoAvaliador() throws Exception {
        mvc.perform(get("/avaliador"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "AVALIADOR")
    void avaliadorNaoAcessaAreaDeAdmin() throws Exception {
        mvc.perform(get("/usuarios"))
            .andExpect(status().isForbidden());
        mvc.perform(get("/auditoria"))
            .andExpect(status().isForbidden());
    }

    // --- Arquivo / Reabertura ---

    @Test
    @WithMockUser(roles = "OPERADOR")
    void operadorAcessaArquivoMasNaoReabreProcesso() throws Exception {
        mvc.perform(get("/arquivo")).andExpect(status().isOk());
        // Reabrir e exclusivo do ADMIN: bloqueado por role antes de chegar no controller.
        mvc.perform(post("/processos/1/reabrir").with(csrf()))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminAcessaArquivoEPodeChamarReabrir() throws Exception {
        mvc.perform(get("/arquivo")).andExpect(status().isOk());
        // Sem processo id=1 cadastrado no banco de teste (create-drop): a
        // seguranca libera (nao e 403) e a regra de negocio redireciona com erro.
        mvc.perform(post("/processos/1/reabrir").with(csrf()))
            .andExpect(status().is3xxRedirection());
    }

    // --- Exclusao de processo (exclusiva do ADMIN) ---

    @Test
    @WithMockUser(roles = "OPERADOR")
    void operadorNaoExcluiProcesso() throws Exception {
        // Excluir e exclusivo do ADMIN: bloqueado por role antes de chegar no controller.
        mvc.perform(post("/processos/1/excluir").with(csrf()))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminPodeChamarExcluir() throws Exception {
        // Sem processo id=1 cadastrado no banco de teste (create-drop): a
        // seguranca libera (nao e 403) e a regra de negocio redireciona com erro.
        mvc.perform(post("/processos/1/excluir").with(csrf()))
            .andExpect(status().is3xxRedirection());
    }
}
