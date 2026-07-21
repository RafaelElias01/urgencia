package br.gov.saude.sgpur.service.auditoria;

import br.gov.saude.sgpur.service.AuditoriaService;
import org.aspectj.lang.JoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * Testes do AuditoriaAspect. O aspect e chamado diretamente com um JoinPoint
 * mockado e a anotacao {@link LogAuditoria} real (obtida via reflexao de
 * metodos de um "alvo" de teste), o que exercita toda a logica de negocio do
 * aspect - avaliacao SpEL do detalhe, fallback quando a expressao e invalida,
 * e captura do IP da requisicao HTTP corrente - sem precisar de um proxy AOP
 * completo. A parte que SO um proxy Spring AOP pode confirmar (o pointcut
 * "@annotation(logAuditoria)" realmente intercepta a chamada) fica no teste
 * de integracao {@code AuditoriaAspectProxyTest}, que sobe um mini contexto
 * Spring so com o aspect + um bean alvo.
 */
class AuditoriaAspectTest {

    /** Classe alvo usada apenas para obter instancias reais da anotacao via reflexao. */
    static class Alvo {
        @LogAuditoria(acao = "ACAO_SIMPLES")
        void semDetalhe(String x) {
        }

        @LogAuditoria(acao = "ACAO_COM_SPEL", detalhe = "'Processo id ' + #args[0]")
        void comDetalheSpel(Long id) {
        }

        @LogAuditoria(acao = "ACAO_SPEL_INVALIDO", detalhe = "#args[0].propriedadeQueNaoExiste(")
        void comSpelInvalido(String x) {
        }

        @LogAuditoria(acao = "ACAO_DETALHE_LITERAL", detalhe = "'texto fixo sem SpEL'")
        void comDetalheLiteral() {
        }
    }

    private AuditoriaService auditoriaService;
    private AuditoriaAspect aspect;

    @BeforeEach
    void setUp() {
        auditoriaService = mock(AuditoriaService.class);
        aspect = new AuditoriaAspect(auditoriaService);
    }

    @AfterEach
    void limparRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    private LogAuditoria anotacaoDe(String nomeMetodo, Class<?>... parametros) throws NoSuchMethodException {
        Method m = Alvo.class.getDeclaredMethod(nomeMetodo, parametros);
        return m.getAnnotation(LogAuditoria.class);
    }

    private JoinPoint joinPointComArgs(Object... args) {
        JoinPoint jp = mock(JoinPoint.class);
        when(jp.getArgs()).thenReturn(args);
        return jp;
    }

    @Test
    void registrarSemDetalheDelegaComDetalheNulo() throws Exception {
        LogAuditoria ann = anotacaoDe("semDetalhe", String.class);
        JoinPoint jp = joinPointComArgs("qualquer");

        aspect.registrar(jp, ann);

        verify(auditoriaService).registrar(eq("ACAO_SIMPLES"), isNull(), isNull());
    }

    @Test
    void registrarAvaliaExpressaoSpelComArgumentosDoMetodo() throws Exception {
        LogAuditoria ann = anotacaoDe("comDetalheSpel", Long.class);
        JoinPoint jp = joinPointComArgs(42L);

        aspect.registrar(jp, ann);

        verify(auditoriaService).registrar(eq("ACAO_COM_SPEL"), eq("Processo id 42"), isNull());
    }

    @Test
    void registrarComDetalheLiteralNaoDependeDosArgumentos() throws Exception {
        LogAuditoria ann = anotacaoDe("comDetalheLiteral");
        JoinPoint jp = joinPointComArgs();

        aspect.registrar(jp, ann);

        verify(auditoriaService).registrar(eq("ACAO_DETALHE_LITERAL"), eq("texto fixo sem SpEL"), isNull());
    }

    @Test
    void registrarComSpelInvalidoGravaAExpressaoCrua() throws Exception {
        // SpEL malformado (parenteses nao fechados) nunca deve quebrar a acao
        // principal - o aspect cai para gravar a expressao literal.
        LogAuditoria ann = anotacaoDe("comSpelInvalido", String.class);
        JoinPoint jp = joinPointComArgs("valor");

        aspect.registrar(jp, ann);

        verify(auditoriaService).registrar(eq("ACAO_SPEL_INVALIDO"),
            eq("#args[0].propriedadeQueNaoExiste("), isNull());
    }

    @Test
    void registrarCapturaIpDaRequisicaoHttpCorrente() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.7");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        LogAuditoria ann = anotacaoDe("semDetalhe", String.class);
        JoinPoint jp = joinPointComArgs("x");

        aspect.registrar(jp, ann);

        verify(auditoriaService).registrar(eq("ACAO_SIMPLES"), isNull(), eq("198.51.100.7"));
    }

    @Test
    void registrarSemRequisicaoHttpAtualGravaIpNulo() throws Exception {
        // Fora de uma requisicao HTTP (ex.: chamada de fundo/scheduler) o
        // RequestContextHolder nao tem atributos - nao deve lancar excecao.
        RequestContextHolder.resetRequestAttributes();

        LogAuditoria ann = anotacaoDe("semDetalhe", String.class);
        JoinPoint jp = joinPointComArgs("x");

        aspect.registrar(jp, ann);

        verify(auditoriaService).registrar(eq("ACAO_SIMPLES"), isNull(), isNull());
    }
}
