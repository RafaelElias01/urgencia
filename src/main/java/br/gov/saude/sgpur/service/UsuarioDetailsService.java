package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

/** Carrega usuarios do banco para a autenticacao do Spring Security. */
@Service
public class UsuarioDetailsService implements UserDetailsService {

    private final UsuarioRepository repo;
    private final LoginAttemptService loginAttemptService;

    public UsuarioDetailsService(UsuarioRepository repo, LoginAttemptService loginAttemptService) {
        this.repo = repo;
        this.loginAttemptService = loginAttemptService;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        // Bloqueio por forca bruta (LoginAttemptService): checado ANTES de tocar
        // no banco, para nao vazar via timing se o usuario existe ou nao.
        if (loginAttemptService.estaBloqueado(username)) {
            throw new LockedException(
                "Conta temporariamente bloqueada por excesso de tentativas. Tente novamente em alguns minutos.");
        }
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
