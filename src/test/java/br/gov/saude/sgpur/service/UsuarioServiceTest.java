package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Cobre o fluxo seguro de "esqueci minha senha": a senha nova NUNCA e
 * exposta em texto puro pelo metodo (o antigo comportamento retornava a
 * senha para a tela mostrar); em vez disso e enviada por e-mail. Tambem
 * cobre os casos sem usuario/sem e-mail cadastrado, que devem ser
 * silenciosos (sem excecao) para nao permitir enumeracao de usuarios.
 */
@ExtendWith(MockitoExtension.class)
class UsuarioServiceTest {

    @Mock private UsuarioRepository repo;
    @Mock private PasswordEncoder encoder;
    @Mock private MembroUrgenciaRenalRepository membroRepo;
    @Mock private EmailSenderService emailSenderService;

    private UsuarioService service;

    @BeforeEach
    void setUp() {
        service = new UsuarioService(repo, encoder, membroRepo, emailSenderService);
    }

    private Usuario usuarioComEmail() {
        Usuario u = new Usuario();
        u.setUsername("operador1");
        u.setNome("Operador Um");
        u.setEmail("operador1@example.com");
        return u;
    }

    @Test
    void resetarSenhaEnviaPorEmailSemExporSenhaEmTextoPuro() {
        Usuario u = usuarioComEmail();
        when(repo.findByUsername("operador1")).thenReturn(Optional.of(u));
        when(encoder.encode(any())).thenReturn("hash-fake");
        when(emailSenderService.enviar(anyString(), anyString(), anyString())).thenReturn(true);

        service.resetarSenha("operador1");

        verify(repo).save(u);
        assertThat(u.getSenha()).isEqualTo("hash-fake");

        ArgumentCaptor<String> corpoCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailSenderService).enviar(eq("operador1@example.com"), anyString(), corpoCaptor.capture());
        // A senha temporaria gerada aparece no corpo do e-mail, nunca em um valor de retorno do metodo.
        assertThat(corpoCaptor.getValue()).contains("Nova senha temporaria:");
    }

    @Test
    void resetarSenhaSemUsuarioNaoLancaExcecaoNemEnviaEmail() {
        when(repo.findByUsername("inexistente")).thenReturn(Optional.empty());

        service.resetarSenha("inexistente");

        verifyNoInteractions(emailSenderService);
        verify(repo, never()).save(any());
    }

    @Test
    void resetarSenhaSemEmailCadastradoNaoAlteraSenhaNemEnvia() {
        Usuario u = new Usuario();
        u.setUsername("sememail");
        u.setNome("Sem Email");
        when(repo.findByUsername("sememail")).thenReturn(Optional.of(u));

        service.resetarSenha("sememail");

        verify(repo, never()).save(any());
        verifyNoInteractions(emailSenderService);
    }

    @Test
    void resetarSenhaComFalhaNoEnvioNaoAlteraSenha() {
        Usuario u = usuarioComEmail();
        String senhaOriginal = u.getSenha();
        when(repo.findByUsername("operador1")).thenReturn(Optional.of(u));
        when(emailSenderService.enviar(anyString(), anyString(), anyString())).thenReturn(false);

        service.resetarSenha("operador1");

        verify(repo, never()).save(any());
        assertThat(u.getSenha()).isEqualTo(senhaOriginal);
    }
}
