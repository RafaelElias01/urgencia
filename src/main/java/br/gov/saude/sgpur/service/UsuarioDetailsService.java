package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Carrega usuarios do banco para a autenticacao do Spring Security.
 *
 * <p>Nao ha pre-checagem de bloqueio por forca bruta: ela foi removida junto
 * com o proprio bloqueio (ver javadoc de {@link LoginAttemptService}). O
 * {@code disabled(!u.isAtivo())} abaixo e outra coisa - barra o usuario
 * INATIVADO no cadastro, e continua valendo.
 */
@Service
public class UsuarioDetailsService implements UserDetailsService {

    private final UsuarioRepository repo;

    public UsuarioDetailsService(UsuarioRepository repo) {
        this.repo = repo;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        Usuario u = repo.findByUsername(username)
            .orElseThrow(() -> new UsernameNotFoundException("Usuario nao encontrado: " + username));
        return User.builder()
            .username(u.getUsername())
            .password(u.getSenha())
            .disabled(!u.isAtivo())
            .authorities(List.of(new SimpleGrantedAuthority(u.getPerfil().getAuthority())))
            .build();
    }
}
