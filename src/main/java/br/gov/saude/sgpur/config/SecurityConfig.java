package br.gov.saude.sgpur.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

import java.io.IOException;

/**
 * Seguranca da aplicacao: login por formulario com usuarios persistidos no
 * banco (ver UsuarioDetailsService). O admin inicial e semeado por DataSeed.
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

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/css/**", "/js/**", "/webjars/**", "/favicon.ico").permitAll()
                .requestMatchers(AntPathRequestMatcher.antMatcher("/h2-console/**")).permitAll()
                .requestMatchers("/usuarios/**", "/auditoria/**").hasRole("ADMIN")
                .requestMatchers("/membros/**", "/relatorios/**").hasRole("ADMIN")
                .requestMatchers("/controle-urgencias/**").hasAnyRole("ADMIN", "OPERADOR")
                .requestMatchers("/", "/processos/**").hasAnyRole("ADMIN", "OPERADOR")
                .requestMatchers("/avaliador/**").hasRole("AVALIADOR")
                .anyRequest().authenticated()
            )
            .formLogin(form -> form
                .loginPage("/login")
                .successHandler(perfilSuccessHandler())
                .permitAll()
            )
            .logout(logout -> logout
                .logoutSuccessUrl("/login?logout")
                .permitAll()
            )
            // H2 console usa frames e nao envia CSRF token
            .csrf(csrf -> csrf.ignoringRequestMatchers(AntPathRequestMatcher.antMatcher("/h2-console/**")))
            .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()));
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
}
