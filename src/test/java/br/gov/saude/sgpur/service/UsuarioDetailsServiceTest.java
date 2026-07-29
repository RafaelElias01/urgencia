package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.Perfil;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Cobre o {@link UsuarioDetailsService}, ponto de integracao entre o Spring
 * Security e o cadastro de usuarios: autoridades/estado carregados
 * corretamente e usuario inexistente.
 *
 * <p>Nao ha mais teste de "conta bloqueada por excesso de tentativas": o
 * bloqueio por forca bruta foi removido do sistema (ver javadoc de
 * {@link LoginAttemptService}), e com ele o {@code LockedException} que era
 * lancado aqui. O que continua valendo e o usuario INATIVO ser carregado como
 * {@code disabled} - coisa diferente, coberta abaixo.
 */
@ExtendWith(MockitoExtension.class)
class UsuarioDetailsServiceTest {

    @Mock private UsuarioRepository repo;

    private UsuarioDetailsService service;

    @BeforeEach
    void setUp() {
        service = new UsuarioDetailsService(repo);
    }

    private Usuario usuario(String username, String senhaHash, Perfil perfil, boolean ativo) {
        Usuario u = new Usuario();
        u.setUsername(username);
        u.setSenha(senhaHash);
        u.setNome("Fulano de Tal");
        u.setPerfil(perfil);
        u.setAtivo(ativo);
        return u;
    }

    @Test
    void carregaUsuarioAtivoComAutoridadeCorrespondenteAoPerfil() {
        Usuario u = usuario("operador1", "hash-senha", Perfil.OPERADOR, true);
        when(repo.findByUsername("operador1")).thenReturn(Optional.of(u));

        UserDetails details = service.loadUserByUsername("operador1");

        assertThat(details.getUsername()).isEqualTo("operador1");
        assertThat(details.getPassword()).isEqualTo("hash-senha");
        assertThat(details.isEnabled()).isTrue();
        assertThat(details.getAuthorities())
            .extracting(GrantedAuthority::getAuthority)
            .containsExactly("ROLE_OPERADOR");
    }

    @Test
    void carregaUsuarioAdminComAutoridadeRoleAdmin() {
        Usuario u = usuario("admin", "hash-admin", Perfil.ADMIN, true);
        when(repo.findByUsername("admin")).thenReturn(Optional.of(u));

        UserDetails details = service.loadUserByUsername("admin");

        assertThat(details.getAuthorities())
            .extracting(GrantedAuthority::getAuthority)
            .containsExactly("ROLE_ADMIN");
    }

    @Test
    void carregaUsuarioAvaliadorComAutoridadeRoleAvaliador() {
        Usuario u = usuario("avaliador1", "hash-aval", Perfil.AVALIADOR, true);
        when(repo.findByUsername("avaliador1")).thenReturn(Optional.of(u));

        UserDetails details = service.loadUserByUsername("avaliador1");

        assertThat(details.getAuthorities())
            .extracting(GrantedAuthority::getAuthority)
            .containsExactly("ROLE_AVALIADOR");
    }

    @Test
    void usuarioInativoEhCarregadoComoDisabled() {
        Usuario u = usuario("inativo1", "hash", Perfil.OPERADOR, false);
        when(repo.findByUsername("inativo1")).thenReturn(Optional.of(u));

        UserDetails details = service.loadUserByUsername("inativo1");

        assertThat(details.isEnabled()).isFalse();
    }

    @Test
    void usernameDesconhecidoLancaUsernameNotFoundException() {
        when(repo.findByUsername("fantasma")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadUserByUsername("fantasma"))
            .isInstanceOf(UsernameNotFoundException.class)
            .hasMessageContaining("fantasma");
    }
}
