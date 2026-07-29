package br.gov.saude.sgpur.service;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.web.authentication.WebAuthenticationDetails;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * O bloqueio por forca bruta foi removido do sistema (decisao de produto, ver
 * javadoc de {@link LoginAttemptService}); o que sobra - e o que estes testes
 * cobrem - e a trilha de auditoria: nenhuma tentativa de login pode derrubar a
 * autenticacao com excecao, e o IP precisa continuar sendo capturado pelo
 * filtro para aparecer no log.
 */
class LoginAttemptServiceTest {

    private final LoginAttemptService service = new LoginAttemptService();

    /**
     * Simula uma requisicao HTTP passando pelo filtro (que captura o IP no
     * ThreadLocal) e executa {@code dentroDoChain} dentro do chain, igual ao
     * Security faria durante a autenticacao.
     */
    private void requisicaoDe(String ip, Runnable dentroDoChain) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(ip);
        FilterChain chain = (req, res) -> dentroDoChain.run();
        try {
            service.doFilter(request, new MockHttpServletResponse(), chain);
        } catch (IOException | ServletException e) {
            throw new RuntimeException(e);
        }
    }

    private void falhar(String username, String ip) {
        var auth = new UsernamePasswordAuthenticationToken(username, "senha-errada");
        auth.setDetails(new WebAuthenticationDetails(criarRequestComIp(ip)));
        service.aoFalhar(new AuthenticationFailureBadCredentialsEvent(auth, new BadCredentialsException("bad")));
    }

    private MockHttpServletRequest criarRequestComIp(String ip) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRemoteAddr(ip);
        return r;
    }

    private void logarComSucesso(String username, String ip) {
        var auth = new UsernamePasswordAuthenticationToken(username, "senha-certa");
        auth.setDetails(new WebAuthenticationDetails(criarRequestComIp(ip)));
        service.aoLogarComSucesso(new AuthenticationSuccessEvent(auth));
    }

    /** Le o ThreadLocal privado que guarda o IP da requisicao corrente. */
    @SuppressWarnings("unchecked")
    private String ipCapturadoAgora() {
        try {
            Field f = LoginAttemptService.class.getDeclaredField("IP_REQUISICAO_ATUAL");
            f.setAccessible(true);
            return ((ThreadLocal<String>) f.get(null)).get();
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void filtroCapturaOIpDaRequisicaoDuranteAExecucaoDoChain() {
        AtomicReference<String> visto = new AtomicReference<>();
        requisicaoDe("10.0.0.1", () -> visto.set(ipCapturadoAgora()));

        assertThat(visto.get()).isEqualTo("10.0.0.1");
    }

    @Test
    void filtroLimpaOThreadLocalDepoisDaRequisicao() {
        requisicaoDe("10.0.0.1", () -> {});

        assertThat(ipCapturadoAgora()).isNull();
    }

    @Test
    void filtroLimpaOThreadLocalMesmoQuandoOChainLancaExcecao() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.9");
        FilterChain chain = (req, res) -> {
            throw new IllegalStateException("erro dentro do chain");
        };

        assertThatCode(() -> service.doFilter(request, new MockHttpServletResponse(), chain))
            .isInstanceOf(IllegalStateException.class);
        assertThat(ipCapturadoAgora()).isNull();
    }

    @Test
    void filtroSempreSegueACadeia() {
        AtomicBoolean chamou = new AtomicBoolean(false);
        requisicaoDe("10.0.0.1", () -> chamou.set(true));

        assertThat(chamou).isTrue();
    }

    @Test
    void muitasFalhasSeguidasNaoBloqueiamNemLancamExcecao() {
        // O bloqueio por tentativas foi removido: errar a senha muitas vezes
        // continua sendo apenas auditado, nunca trava a conta.
        assertThatCode(() -> {
            for (int i = 0; i < 20; i++) {
                falhar("admin", "10.0.0.1");
            }
        }).doesNotThrowAnyException();
    }

    @Test
    void sucessoDepoisDeVariasFalhasNaoLancaExcecao() {
        for (int i = 0; i < 5; i++) {
            falhar("admin", "10.0.0.1");
        }

        assertThatCode(() -> logarComSucesso("admin", "10.0.0.1")).doesNotThrowAnyException();
    }

    @Test
    void eventoDeFalhaForaDeUmaRequisicaoNaoLanca() {
        // Sem WebAuthenticationDetails e sem ThreadLocal (ex.: login
        // programatico), o IP fica nulo - o log ainda assim precisa sair.
        var auth = new UsernamePasswordAuthenticationToken("ninguem", "x");

        assertThatCode(() ->
            service.aoFalhar(new AuthenticationFailureBadCredentialsEvent(auth, new BadCredentialsException("bad"))))
            .doesNotThrowAnyException();
    }
}
