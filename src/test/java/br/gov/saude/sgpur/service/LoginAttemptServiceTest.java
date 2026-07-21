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

import static org.assertj.core.api.Assertions.assertThat;

class LoginAttemptServiceTest {

    private final LoginAttemptService service = new LoginAttemptService();

    /**
     * Simula uma requisicao HTTP passando pelo filtro (que captura o IP no
     * ThreadLocal) e executa {@code dentro} do chain, igual ao Security faria
     * durante a autenticacao - assim {@code estaBloqueado} consegue ler o
     * mesmo IP usado para registrar as falhas.
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

    @Test
    void naoBloqueiaAntesDoLimite() {
        for (int i = 0; i < 4; i++) {
            falhar("admin", "10.0.0.1");
        }
        requisicaoDe("10.0.0.1", () -> assertThat(service.estaBloqueado("admin")).isFalse());
    }

    @Test
    void bloqueiaAposCincoFalhasMesmoUsuarioMesmoIp() {
        for (int i = 0; i < 5; i++) {
            falhar("admin", "10.0.0.1");
        }
        requisicaoDe("10.0.0.1", () -> assertThat(service.estaBloqueado("admin")).isTrue());
    }

    @Test
    void bloqueioEIsoladoPorIp() {
        // 5 falhas vindas de um IP nao devem bloquear o mesmo usuario tentando
        // de outro IP - e o motivo inteiro de a chave ser username+IP.
        for (int i = 0; i < 5; i++) {
            falhar("admin", "10.0.0.1");
        }
        requisicaoDe("10.0.0.2", () -> assertThat(service.estaBloqueado("admin")).isFalse());
    }

    @Test
    void loginComSucessoLimpaContagemDeFalhas() {
        for (int i = 0; i < 4; i++) {
            falhar("admin", "10.0.0.1");
        }
        logarComSucesso("admin", "10.0.0.1");
        falhar("admin", "10.0.0.1"); // so 1 falha desde a limpeza

        requisicaoDe("10.0.0.1", () -> assertThat(service.estaBloqueado("admin")).isFalse());
    }

    @Test
    void usernameEComparadoIgnorandoMaiusculasEMinusculas() {
        for (int i = 0; i < 5; i++) {
            falhar("Admin", "10.0.0.1");
        }
        requisicaoDe("10.0.0.1", () -> assertThat(service.estaBloqueado("ADMIN")).isTrue());
    }

    @Test
    void semRequisicaoAtualEstaBloqueadoNaoLancaEUsaFallbackVazio() {
        // estaBloqueado fora de uma requisicao (ThreadLocal vazio) nao deve
        // lancar excecao - so nao vai achar nenhum estado bloqueado.
        assertThat(service.estaBloqueado("ninguem")).isFalse();
    }
}
