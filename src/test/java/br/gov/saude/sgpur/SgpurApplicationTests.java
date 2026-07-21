package br.gov.saude.sgpur;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Smoke test: sobe o contexto completo do Spring (controllers, services,
 * seguranca, JPA, seed) contra um H2 em memoria. Falha se houver erro de
 * fiacao/configuracao de qualquer bean.
 */
@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:sgpur-test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
class SgpurApplicationTests {

    @Test
    void contextLoads() {
        // Se o contexto subir sem excecao, o teste passa.
    }
}
