package br.gov.saude.sgpur.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

import java.io.IOException;

/**
 * Seguranca da aplicacao: login por formulario com usuarios persistidos no
 * banco (ver UsuarioDetailsService). O primeiro ADMIN e criado por
 * {@link AdminBootstrap} somente quando a tabela usuario esta vazia (o
 * DataSeed antigo, que sempre recriava dados de demo, foi removido).
 *
 * Perfis e rotas protegidas:
 *  - ADMIN    : acesso total, incluindo /usuarios/** e /auditoria/**.
 *  - OPERADOR : acesso operacional (processos, membros, relatorios).
 *               NAO acessa /avaliador/**.
 *  - AVALIADOR: acesso restrito ao portal /avaliador/**.
 *               NAO acessa /usuarios/**, /auditoria/** nem areas operacionais.
 *
 * Apos login, AVALIADOR e redirecionado para /avaliador; os demais para /.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Content-Security-Policy de producao. Restringe as origens ao proprio
     * host + Google Fonts (a fonte Inter e carregada de fonts.googleapis/
     * gstatic no layout). Mantem 'unsafe-inline' para script/style porque os
     * templates usam scripts e estilos inline; como nao ha nenhum th:utext no
     * projeto, a superficie de XSS refletido e minima.
     */
    private static final String CSP_PROD = String.join("; ",
        "default-src 'self'",
        "img-src 'self' data:",
        "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com",
        "font-src 'self' https://fonts.gstatic.com",
        "script-src 'self' 'unsafe-inline'",
        "base-uri 'self'",
        "form-action 'self'",
        "frame-ancestors 'none'");

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, Environment env) throws Exception {
        // O console H2 (frames + sem CSRF) so existe em dev. Em producao ele nao
        // e registrado (spring.h2.console.enabled=false), e aqui tambem NAO
        // abrimos as excecoes de seguranca correspondentes (defesa em profundidade).
        boolean dev = env.matchesProfiles("dev");

        http
            .authorizeHttpRequests(auth -> {
                auth.requestMatchers("/css/**", "/js/**", "/webjars/**", "/favicon.ico").permitAll();
                if (dev) {
                    auth.requestMatchers("/h2-console/**").permitAll();
                }
                // Precisa vir ANTES da regra geral /usuarios/** (ADMIN) - senao ninguem
                // deslogado consegue acessar a recuperacao de senha, justamente quando
                // mais precisa (nao consegue logar).
                auth.requestMatchers("/usuarios/esqueci-senha").permitAll()
                    .requestMatchers("/login").permitAll()
                    // Troca da propria senha: qualquer usuario logado (ADMIN/OPERADOR/
                    // AVALIADOR). Precisa vir ANTES da regra /usuarios/** (ADMIN), senao
                    // OPERADOR/AVALIADOR ficariam sem como trocar a propria senha.
                    .requestMatchers("/usuarios/minha-senha").authenticated()
                    .requestMatchers("/usuarios/**", "/auditoria/**").hasRole("ADMIN")
                    // Membros e relatorios sao "acesso operacional" (comentario da classe):
                    // OPERADOR tem acesso completo (criar/editar/inativar membros, gerar
                    // relatorios), igual ao ADMIN. So /usuarios/** (cadastro de LOGINS) e
                    // /auditoria/** ficam exclusivos do ADMIN.
                    .requestMatchers("/membros/**", "/relatorios/**").hasAnyRole("ADMIN", "OPERADOR")
                    .requestMatchers("/controle-urgencias/**").hasAnyRole("ADMIN", "OPERADOR")
                    // Reabrir processo encerrado e exclusivo do ADMIN. Precisa vir
                    // ANTES da regra geral /processos/** (ADMIN,OPERADOR), senao o
                    // OPERADOR herdaria o acesso.
                    .requestMatchers(HttpMethod.POST, "/processos/*/reabrir").hasRole("ADMIN")
                    // Excluir processo tambem e exclusivo do ADMIN (OPERADOR edita,
                    // mas nao exclui). Mesma logica de ordenacao do reabrir acima.
                    .requestMatchers(HttpMethod.POST, "/processos/*/excluir").hasRole("ADMIN")
                    .requestMatchers("/arquivo/**").hasAnyRole("ADMIN", "OPERADOR")
                    .requestMatchers("/", "/processos/**").hasAnyRole("ADMIN", "OPERADOR")
                    .requestMatchers("/avaliador/**").hasRole("AVALIADOR")
                    .anyRequest().authenticated();
            })
            .formLogin(form -> form
                .loginPage("/login")
                .successHandler(perfilSuccessHandler())
                .failureHandler(loginFailureHandler())
                .permitAll()
            )
            .logout(logout -> logout
                .logoutSuccessUrl("/login?logout")
                .permitAll()
            )
            .csrf(csrf -> {
                // H2 console usa frames e nao envia CSRF token - excecao so em dev
                if (dev) {
                    csrf.ignoringRequestMatchers("/h2-console/**");
                }
            })
            .headers(headers -> {
                if (dev) {
                    // Console H2 precisa renderizar em frame do mesmo host
                    headers.frameOptions(frame -> frame.sameOrigin());
                } else {
                    // Producao: bloqueia enquadramento (clickjacking), forca HTTPS
                    // (HSTS) e aplica a CSP. Dev fica de fora para nao quebrar o
                    // console H2 nem o live-reload do devtools.
                    headers.frameOptions(frame -> frame.deny());
                    headers.httpStrictTransportSecurity(hsts -> hsts
                        .includeSubDomains(true)
                        .maxAgeInSeconds(31_536_000));
                    headers.contentSecurityPolicy(csp -> csp.policyDirectives(CSP_PROD));
                }
            });
        return http.build();
    }

    /**
     * Redireciona o usuario apos login conforme o perfil:
     *  - AVALIADOR -> /avaliador (portal restrito, sem dados sigilosos)
     *  - demais    -> / (dashboard operacional)
     */
    @Bean
    public AuthenticationSuccessHandler perfilSuccessHandler() {
        return new AuthenticationSuccessHandler() {
            @Override
            public void onAuthenticationSuccess(HttpServletRequest request,
                                                HttpServletResponse response,
                                                Authentication authentication) throws IOException {
                boolean isAvaliador = authentication.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("ROLE_AVALIADOR"));
                response.sendRedirect(request.getContextPath() + (isAvaliador ? "/avaliador" : "/"));
            }
        };
    }

    /**
     * Distingue o bloqueio por forca bruta ({@link LoginAttemptService}) de uma
     * simples senha errada, para a tela de login mostrar a mensagem certa
     * (ver login.html: param.bloqueado vs param.error).
     */
    @Bean
    public AuthenticationFailureHandler loginFailureHandler() {
        return (request, response, exception) -> {
            String destino = (exception instanceof LockedException) ? "bloqueado" : "error";
            response.sendRedirect(request.getContextPath() + "/login?" + destino);
        };
    }
}
