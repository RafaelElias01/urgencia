package br.gov.saude.sgpur.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cobre o ramo de PRODUCAO do SecurityConfig (headers de clickjacking/CSP).
 *
 * <p>Nenhum outro teste exercita esse ramo: application.yml ativa o profile
 * "dev" por padrao, inclusive em @SpringBootTest sem @ActiveProfiles
 * explicito (ver SecurityIntegrationTest) - e o ramo dev tem headers
 * diferentes (frameOptions.sameOrigin() global, por causa do console H2).
 * Sem este teste, uma regressao no ramo de producao (ex.: reverter
 * frame-ancestors pra 'self' de novo, ou pra 'none' sem a excecao da rota do
 * PDF) passaria despercebida por toda a suite - inclusive pelo e2e do Portal
 * do Avaliador (FluxoCompletoProcessoIT), que tambem roda em dev.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    // Nenhum profile "dev" nem "prod" ativo: SecurityConfig.dev fica false
    // (executa o ramo de producao) sem carregar application-prod.yml (que
    // exigiria SGPUR_ADMIN_PASSWORD/Postgres real).
    "spring.profiles.active=test-security-headers",
    "spring.datasource.url=jdbc:h2:mem:sgpur-secheaders;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
class SecurityConfigProdHeadersTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void restoDoAppBloqueiaEnquadramentoPorQualquerOrigem() throws Exception {
        mvc.perform(get("/login"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Frame-Options", "DENY"))
            .andExpect(header().string("Content-Security-Policy",
                containsString("frame-ancestors 'none'")));
    }

    @Test
    void rotaDoPdfDoAvaliadorPermiteEnquadramentoSameOrigin() throws Exception {
        // Sem autenticacao a rota redireciona pro /login - mas o
        // HeaderWriterFilter roda antes da autenticacao/autorizacao, entao
        // os headers ja aparecem na resposta mesmo no redirect.
        mvc.perform(get("/avaliador/1/pdf/1"))
            .andExpect(header().string("X-Frame-Options", "SAMEORIGIN"))
            .andExpect(header().string("Content-Security-Policy",
                containsString("frame-ancestors 'self'")));
    }
}
