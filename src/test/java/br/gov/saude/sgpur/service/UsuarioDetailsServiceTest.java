package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.Perfil;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Cobre o {@link UsuarioDetailsService}, ponto de integracao entre o Spring
 * Security e o cadastro de usuarios: autoridades/estado carregados
 * corretamente, usuario inexistente, e a pre-checagem de bloqueio por forca
 * bruta via {@link LoginAttemptService} - que precisa acontecer ANTES de
 * qualquer consulta ao banco (ver javadoc de LoginAttemptService.estaBloqueado).
 */
@ExtendWith(MockitoExtension.class)
class UsuarioDetailsServiceTest {

    @Mock private UsuarioRepository repo;
    @Mock private LoginAttemptService loginAttemptService;

    private UsuarioDetailsService service;

    @BeforeEach
    void setUp() {
        service = new UsuarioDetailsService(repo, loginAttemptService);
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
        when(loginAttemptService.estaBloqueado("operador1")).thenReturn(false);
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
        when(loginAttemptService.estaBloqueado("admin")).thenReturn(false);
        when(repo.findByUsername("admin")).thenReturn(Optional.of(u));

        UserDetails details = service.loadUserByUsername("admin");

        assertThat(details.getAuthorities())
            .extracting(GrantedAuthority::getAuthority)
            .containsExactly("ROLE_ADMIN");
    }

    @Test
    void carregaUsuarioAvaliadorComAutoridadeRoleAvaliador() {
        Usuario u = usuario("avaliador1", "hash-aval", Perfil.AVALIADOR, true);
        when(loginAttemptService.estaBloqueado("avaliador1")).thenReturn(false);
        when(repo.findByUsername("avaliador1")).thenReturn(Optional.of(u));

        UserDetails details = service.loadUserByUsername("avaliador1");

        assertThat(details.getAuthorities())
            .extracting(GrantedAuthority::getAuthority)
            .containsExactly("ROLE_AVALIADOR");
    }

    @Test
    void usuarioInativoEhCarregadoComoDisabled() {
        Usuario u = usuario("inativo1", "hash", Perfil.OPERADOR, false);
        when(loginAttemptService.estaBloqueado("inativo1")).thenReturn(false);
        when(repo.findByUsername("inativo1")).thenReturn(Optional.of(u));

        UserDetails details = service.loadUserByUsername("inativo1");

        assertThat(details.isEnabled()).isFalse();
    }

    @Test
    void usernameDesconhecidoLancaUsernameNotFoundException() {
        when(loginAttemptService.estaBloqueado("fantasma")).thenReturn(false);
        when(repo.findByUsername("fantasma")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadUserByUsername("fantasma"))
            .isInstanceOf(UsernameNotFoundException.class)
            .hasMessageContaining("fantasma");
    }

    @Test
    void contaBloqueadaPorLoginAttemptServiceLancaLockedExceptionSemConsultarOBanco() {
        when(loginAttemptService.estaBloqueado("vitima")).thenReturn(true);

        assertThatThrownBy(() -> service.loadUserByUsername("vitima"))
            .isInstanceOf(LockedException.class)
            .hasMessageContaining("bloqueada");

        // A checagem de forca bruta deve ocorrer ANTES de tocar no banco -
        // nem repo.findByUsername pode ser chamado nesse caso (ver javadoc de
        // LoginAttemptService: evita vazar por timing se o usuario existe ou nao).
        verifyNoInteractions(repo);
    }

    @Test
    void contaBloqueadaImpedeCarregamentoMesmoQuandoUsuarioExisteEEstaAtivo() {
        // Mesmo que o usuario exista e esteja ativo no banco, o bloqueio por
        // forca bruta tem prioridade e nunca deve permitir o carregamento.
        when(loginAttemptService.estaBloqueado("existente")).thenReturn(true);

        assertThatThrownBy(() -> service.loadUserByUsername("existente"))
            .isInstanceOf(LockedException.class);

        verify(repo, never()).findByUsername(anyString());
    }
}
