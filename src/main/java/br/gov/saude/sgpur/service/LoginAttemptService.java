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
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Protecao basica contra forca bruta no login: apos {@value #MAX_TENTATIVAS}
 * tentativas com senha errada para a MESMA COMBINACAO usuario+IP, bloqueia
 * novas tentativas dessa combinacao por 15 minutos (janela deslizante em
 * memoria).
 *
 * <p><b>Por que username+IP (e nao so username):</b> a versao anterior
 * bloqueava so por username. Isso permitia que QUALQUER atacante anonimo
 * bloqueasse a conta de outra pessoa (ex.: o ADMIN de verdade) so errando a
 * senha 5 vezes a partir do proprio IP - um DoS trivial e repetivel contra uma
 * conta especifica. Combinando username+IP na chave, o bloqueio fica isolado
 * por origem: o atacante so consegue bloquear a si mesmo (aquele IP), sem
 * afetar o login legitimo do dono da conta a partir de outro lugar.
 *
 * <p><b>Como o IP chega ate aqui:</b> {@code UsuarioDetailsService.loadUserByUsername}
 * (que faz a pre-checagem via {@link #estaBloqueado}) roda DENTRO da cadeia de
 * filtros do Spring Security, ANTES do DispatcherServlet - {@code RequestContextHolder}
 * ainda NAO esta disponivel nesse ponto (so e vinculado pelo DispatcherServlet,
 * que sequer chega a ser acionado para a URL de login, tratada inteiramente
 * pelo filtro de autenticacao do Security). Por isso esta propria classe
 * tambem se registra como {@link Filter} (o Spring Boot registra
 * automaticamente qualquer bean {@code Filter} encontrado no contexto) com a
 * maior precedencia possivel ({@link Ordered#HIGHEST_PRECEDENCE}), rodando
 * ANTES da cadeia do Spring Security, so para capturar
 * {@code request.getRemoteAddr()} num ThreadLocal. Nos eventos de autenticacao
 * (falha/sucesso) o IP e obtido preferencialmente do proprio
 * {@code WebAuthenticationDetails} da autenticacao (mais direto e sempre
 * disponivel nesse ponto), caindo para o ThreadLocal so como reforco.
 *
 * <p>Nao substitui um WAF/rate-limit por IP mais sofisticado, mas encarece um
 * ataque de dicionario contra um login especifico sem abrir uma via de DoS
 * contra contas alheias. Estado em memoria (nao persistido): reinicia a
 * contagem a cada restart da aplicacao - aceitavel para o volume deste sistema
 * (poucos usuarios, uso interno).
 */
@Service
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LoginAttemptService implements Filter {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);

    private static final int MAX_TENTATIVAS = 5;
    private static final Duration BLOQUEIO = Duration.ofMinutes(15);

    private record Estado(int tentativas, Instant bloqueadoAte) {}

    private final ConcurrentHashMap<String, Estado> tentativasPorUsuario = new ConcurrentHashMap<>();

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

    /** True se a combinacao username+IP da requisicao atual esta temporariamente bloqueada. */
    public boolean estaBloqueado(String username) {
        String ip = IP_REQUISICAO_ATUAL.get();
        Estado e = tentativasPorUsuario.get(chave(username, ip));
        return e != null && e.bloqueadoAte() != null && Instant.now().isBefore(e.bloqueadoAte());
    }

    @EventListener
    public void aoFalhar(AbstractAuthenticationFailureEvent evento) {
        String username = String.valueOf(evento.getAuthentication().getPrincipal());
        registrarFalha(username, ipDaAutenticacao(evento.getAuthentication()));
    }

    @EventListener
    public void aoLogarComSucesso(AuthenticationSuccessEvent evento) {
        String username = evento.getAuthentication().getName();
        tentativasPorUsuario.remove(chave(username, ipDaAutenticacao(evento.getAuthentication())));
    }

    private void registrarFalha(String username, String ip) {
        String k = chave(username, ip);
        Estado atualizado = tentativasPorUsuario.compute(k, (key, atual) -> {
            int tentativas = (atual == null ? 0 : atual.tentativas()) + 1;
            Instant bloqueadoAte = tentativas >= MAX_TENTATIVAS
                ? Instant.now().plus(BLOQUEIO) : null;
            return new Estado(tentativas, bloqueadoAte);
        });
        if (atualizado.bloqueadoAte() != null) {
            log.warn("LoginAttemptService: usuario '{}' (ip {}) bloqueado por {} min apos {} tentativas.",
                username, ip, BLOQUEIO.toMinutes(), atualizado.tentativas());
        }
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

    /**
     * Chave do bloqueio: username + IP (separados por '|'), para isolar o
     * bloqueio por origem (ver javadoc da classe). Se o IP nao estiver
     * disponivel (fallback raro, ex.: login programatico sem request HTTP),
     * cai para username sozinho - pior que username+IP, mas ainda bloqueia algo.
     */
    private String chave(String username, String ip) {
        String u = username == null ? "" : username.toLowerCase(Locale.ROOT);
        return (ip == null || ip.isBlank()) ? u : u + "|" + ip;
    }
}
