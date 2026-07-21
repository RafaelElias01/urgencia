package br.gov.saude.sgpur.config;

import br.gov.saude.sgpur.domain.Perfil;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import br.gov.saude.sgpur.service.EmailSenderService;
import br.gov.saude.sgpur.service.UsuarioService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * AdminBootstrap so deve criar o ADMIN inicial quando a tabela usuario esta
 * vazia - nunca deve mexer num banco que ja tem usuarios cadastrados.
 */
@ExtendWith(MockitoExtension.class)
class AdminBootstrapTest {

    @Mock
    private UsuarioRepository usuarioRepository;
    @Mock
    private MembroUrgenciaRenalRepository membroRepository;
    @Mock
    private PasswordEncoder encoder;
    @Mock
    private EmailSenderService emailSenderService;

    @Test
    void criaAdminQuandoBancoVazio() {
        when(usuarioRepository.count()).thenReturn(0L);
        when(encoder.encode("admin123")).thenReturn("hash");
        when(usuarioRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        UsuarioService usuarioService = new UsuarioService(usuarioRepository, encoder, membroRepository,
            emailSenderService, new br.gov.saude.sgpur.service.PasswordResetAttemptService());
        AdminBootstrap bootstrap = new AdminBootstrap(usuarioRepository, usuarioService, "admin", "admin123");

        bootstrap.run(null);

        verify(usuarioRepository).save(argThat((Usuario u) ->
            u.getUsername().equals("admin")
                && u.getPerfil() == Perfil.ADMIN
                && u.isAtivo()
                && u.getSenha().equals("hash")));
    }

    @Test
    void naoCriaAdminQuandoJaExistemUsuarios() {
        when(usuarioRepository.count()).thenReturn(3L);
        UsuarioService usuarioService = new UsuarioService(usuarioRepository, encoder, membroRepository,
            emailSenderService, new br.gov.saude.sgpur.service.PasswordResetAttemptService());
        AdminBootstrap bootstrap = new AdminBootstrap(usuarioRepository, usuarioService, "admin", "admin123");

        bootstrap.run(null);

        verify(usuarioRepository, never()).save(any());
    }
}
