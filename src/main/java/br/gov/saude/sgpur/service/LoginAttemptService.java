package br.gov.saude.sgpur.service;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.stereotype.Service;

import java.io.IOException;

/**
 * Trilha de auditoria das tentativas de login: registra em log TODA tentativa
 * de autenticacao (sucesso e falha) com o usuario informado e o IP de origem.
 *
 * <p><b>Nao existe mais bloqueio por forca bruta.</b> A versao anterior desta
 * classe travava a combinacao usuario+IP por 15 minutos apos 5 senhas erradas;
 * isso foi <b>removido deliberadamente</b> (decisao de produto, nao bug - ver
 * CLAUDE.md): o log de auditoria com IP passou a ser a defesa adotada, para
 * nunca deixar um usuario legitimo trancado fora do sistema. O metodo
 * {@code estaBloqueado} (que ja so devolvia {@code false}), a contagem de
 * falhas em memoria, o {@code LockedException} lancado no
 * {@code UsuarioDetailsService}, o ramo {@code LockedException} do
 * {@code loginFailureHandler} e o alerta {@code param.bloqueado} do
 * {@code login.html} foram todos removidos junto por serem codigo inalcancavel.
 * <b>Se algum dia o bloqueio voltar</b>, sera preciso reintroduzir esses quatro
 * pontos em conjunto - nenhum deles sobrevive isolado.
 *
 * <p><b>Como o IP chega ate aqui:</b> os eventos de autenticacao do Spring
 * Security sao publicados DENTRO da cadeia de filtros, ANTES do
 * DispatcherServlet - {@code RequestContextHolder} ainda NAO esta disponivel
 * nesse ponto (so e vinculado pelo DispatcherServlet, que sequer chega a ser
 * acionado para a URL de login, tratada inteiramente pelo filtro de
 * autenticacao do Security). Por isso esta propria classe tambem se registra
 * como {@link Filter} (o Spring Boot registra automaticamente qualquer bean
 * {@code Filter} encontrado no contexto) com a maior precedencia possivel
 * ({@link Ordered#HIGHEST_PRECEDENCE}), rodando ANTES da cadeia do Spring
 * Security, so para capturar {@code request.getRemoteAddr()} num ThreadLocal.
 * Nos eventos de autenticacao (falha/sucesso) o IP e obtido preferencialmente
 * do proprio {@code WebAuthenticationDetails} da autenticacao (mais direto e
 * sempre disponivel nesse ponto), caindo para o ThreadLocal so como reforco.
 */
@Service
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LoginAttemptService implements Filter {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);

    /** IP da requisicao HTTP corrente nesta thread - ver javadoc da classe. */
    private static final ThreadLocal<String> IP_REQUISICAO_ATUAL = new ThreadLocal<>();

    /**
     * Captura o IP remoto da requisicao corrente num ThreadLocal, ANTES da
     * cadeia do Spring Security processar a autenticacao (ver javadoc da
     * classe). Nao interfere em mais nada da requisicao.
     */
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request instanceof HttpServletRequest http) {
            IP_REQUISICAO_ATUAL.set(http.getRemoteAddr());
        }
        try {
            chain.doFilter(request, response);
        } finally {
            IP_REQUISICAO_ATUAL.remove();
        }
    }

    @EventListener
    public void aoFalhar(AbstractAuthenticationFailureEvent evento) {
        String username = String.valueOf(evento.getAuthentication().getPrincipal());
        String ip = ipDaAutenticacao(evento.getAuthentication());
        log.warn("Login falhou para usuario '{}' (ip {}): {}", username, ip,
            evento.getException().getMessage());
    }

    @EventListener
    public void aoLogarComSucesso(AuthenticationSuccessEvent evento) {
        String username = evento.getAuthentication().getName();
        String ip = ipDaAutenticacao(evento.getAuthentication());
        log.info("Login bem-sucedido para usuario '{}' (ip {})", username, ip);
    }

    /**
     * IP associado a uma autenticacao: preferencialmente o {@code WebAuthenticationDetails}
     * (populado pelo Spring Security a partir do request no momento do login),
     * com fallback no ThreadLocal capturado pelo filtro desta classe.
     */
    private String ipDaAutenticacao(Authentication authentication) {
        Object details = authentication == null ? null : authentication.getDetails();
        if (details instanceof WebAuthenticationDetails web && web.getRemoteAddress() != null) {
            return web.getRemoteAddress();
        }
        return IP_REQUISICAO_ATUAL.get();
    }
}
