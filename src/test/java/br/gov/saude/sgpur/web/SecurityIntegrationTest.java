package br.gov.saude.sgpur.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
        mvc.perform(get("/usuarios")).andExpect(status().isForbidden());
        mvc.perform(get("/auditoria")).andExpect(status().isForbidden());
        // mas acessa a area operacional normal
        mvc.perform(get("/processos")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void operadorAcessaRelatorioAnual() throws Exception {
        // A pagina deve renderizar mesmo sem processos cadastrados.
        mvc.perform(get("/relatorios/anual")).andExpect(status().isOk());
    }

    @Test
    void relatorioAnualExigeAutenticacao() throws Exception {
        mvc.perform(get("/relatorios/anual"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrlPattern("**/login"));
    }
}
