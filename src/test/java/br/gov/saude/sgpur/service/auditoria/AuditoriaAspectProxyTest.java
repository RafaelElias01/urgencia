package br.gov.saude.sgpur.service.auditoria;

import br.gov.saude.sgpur.service.AuditoriaService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.stereotype.Component;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Teste de integracao minimo do AuditoriaAspect: sobe um contexto Spring
 * pequeno (so o aspect + um bean de teste anotado com {@link LogAuditoria}),
 * com {@code @EnableAspectJAutoProxy}, para confirmar que o pointcut
 * {@code @annotation(logAuditoria)} realmente intercepta chamadas ao metodo
 * anotado atraves de um proxy AOP de verdade - algo que a chamada direta em
 * {@code AuditoriaAspectTest} nao pode provar. Deliberadamente NAO sobe o
 * @SpringBootTest inteiro da aplicacao; so o suficiente para exercitar o
 * pointcut.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = AuditoriaAspectProxyTest.Config.class)
class AuditoriaAspectProxyTest {

    @Configuration
    @EnableAspectJAutoProxy
    static class Config {
        @Bean
        AuditoriaService auditoriaService() {
            return mock(AuditoriaService.class);
        }

        @Bean
        AuditoriaAspect auditoriaAspect(AuditoriaService auditoriaService) {
            return new AuditoriaAspect(auditoriaService);
        }

        @Bean
        AlvoDeTeste alvoDeTeste() {
            return new AlvoDeTeste();
        }
    }

    @Component
    static class AlvoDeTeste {
        @LogAuditoria(acao = "PROXY_TESTE_ACAO", detalhe = "'valor:' + #args[0]")
        public String metodoAnotado(String valor) {
            return "ok-" + valor;
        }

        public String metodoSemAnotacao(String valor) {
            return "sem-log-" + valor;
        }
    }

    @Autowired
    private AlvoDeTeste alvo;

    @Autowired
    private AuditoriaService auditoriaService;

    @Test
    void chamadaAoMetodoAnotadoDisparaAuditoriaViaProxyAop() {
        String resultado = alvo.metodoAnotado("abc");

        assertEqualsHelper(resultado);
        verify(auditoriaService).registrar(eq("PROXY_TESTE_ACAO"), eq("valor:abc"), any());
    }

    @Test
    void chamadaAoMetodoSemAnotacaoNaoDisparaAuditoria() {
        alvo.metodoSemAnotacao("abc");

        verifyNoInteractions(auditoriaService);
    }

    private void assertEqualsHelper(String resultado) {
        if (!"ok-abc".equals(resultado)) {
            throw new AssertionError("Esperava 'ok-abc', obteve: " + resultado);
        }
    }
}
