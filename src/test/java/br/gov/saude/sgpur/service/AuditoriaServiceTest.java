package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.LogAuditoria;
import br.gov.saude.sgpur.repository.LogAuditoriaRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Testes do AuditoriaService: captura do usuario logado (via
 * SecurityContextHolder), truncamento de detalhe, gravacao do IP e a
 * garantia de que uma falha ao gravar NUNCA propaga (auditoria nao pode
 * quebrar a acao principal).
 */
class AuditoriaServiceTest {

    private LogAuditoriaRepository repo;
    private AuditoriaService service;

    @BeforeEach
    void setUp() {
        repo = mock(LogAuditoriaRepository.class);
        service = new AuditoriaService(repo);
    }

    @AfterEach
    void limparContextoDeSeguranca() {
        SecurityContextHolder.clearContext();
    }

    private void autenticarComo(String username) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
            new UsernamePasswordAuthenticationToken(username, "senha", List.of()));
        SecurityContextHolder.setContext(context);
    }

    @Test
    void registrarGravaUsuarioDoContextoDeSeguranca() {
        autenticarComo("operador1");

        service.registrar("PROCESSO_DECIDIDO", "Processo 01/2026 deferido");

        ArgumentCaptor<LogAuditoria> captor = ArgumentCaptor.forClass(LogAuditoria.class);
        verify(repo).save(captor.capture());
        LogAuditoria log = captor.getValue();
        assertThat(log.getUsuario()).isEqualTo("operador1");
        assertThat(log.getAcao()).isEqualTo("PROCESSO_DECIDIDO");
        assertThat(log.getDetalhe()).isEqualTo("Processo 01/2026 deferido");
        assertThat(log.getIp()).isNull();
    }

    @Test
    void registrarSemAutenticacaoUsaUsuarioSistema() {
        // Nenhuma autenticacao no contexto (chamada de fundo/sistema).
        SecurityContextHolder.clearContext();

        service.registrar("LEMBRETE_ENVIADO", "lote automatico");

        ArgumentCaptor<LogAuditoria> captor = ArgumentCaptor.forClass(LogAuditoria.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getUsuario()).isEqualTo("sistema");
    }

    @Test
    void registrarComAuthenticationSemNomeUsaUsuarioSistema() {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(null, null));
        SecurityContextHolder.setContext(context);

        service.registrar("ACAO_QUALQUER", "detalhe");

        ArgumentCaptor<LogAuditoria> captor = ArgumentCaptor.forClass(LogAuditoria.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getUsuario()).isEqualTo("sistema");
    }

    @Test
    void registrarComIpGravaOIpInformado() {
        autenticarComo("avaliador1");

        service.registrar("PARECER_VOTADO", "Favoravel", "203.0.113.42");

        ArgumentCaptor<LogAuditoria> captor = ArgumentCaptor.forClass(LogAuditoria.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getIp()).isEqualTo("203.0.113.42");
        assertThat(captor.getValue().getUsuario()).isEqualTo("avaliador1");
    }

    @Test
    void registrarSemIpDelegaParaSobrecargaComIpNulo() {
        autenticarComo("operador1");

        service.registrar("ACAO_SEM_IP", "detalhe qualquer");

        ArgumentCaptor<LogAuditoria> captor = ArgumentCaptor.forClass(LogAuditoria.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getIp()).isNull();
    }

    @Test
    void registrarTruncaDetalheAcimaDe400Caracteres() {
        autenticarComo("operador1");
        String detalheGrande = "x".repeat(500);

        service.registrar("ACAO_LONGA", detalheGrande);

        ArgumentCaptor<LogAuditoria> captor = ArgumentCaptor.forClass(LogAuditoria.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getDetalhe()).hasSize(400);
        assertThat(captor.getValue().getDetalhe()).isEqualTo("x".repeat(400));
    }

    @Test
    void registrarComDetalheDeExatamente400CaracteresNaoTrunca() {
        autenticarComo("operador1");
        String detalhe400 = "y".repeat(400);

        service.registrar("ACAO_LIMITE", detalhe400);

        ArgumentCaptor<LogAuditoria> captor = ArgumentCaptor.forClass(LogAuditoria.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getDetalhe()).hasSize(400);
    }

    @Test
    void registrarComDetalheNuloNaoQuebra() {
        autenticarComo("operador1");

        service.registrar("ACAO_SEM_DETALHE", null);

        ArgumentCaptor<LogAuditoria> captor = ArgumentCaptor.forClass(LogAuditoria.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getDetalhe()).isNull();
    }

    @Test
    void registrarNuncaPropagaExcecaoDoRepositorio() {
        autenticarComo("operador1");
        doThrow(new RuntimeException("banco fora")).when(repo).save(any());

        // Nao deve lancar - auditoria nunca pode interromper a acao principal.
        service.registrar("ACAO_QUALQUER", "detalhe");

        verify(repo).save(any());
    }

    @Test
    void registrarNuncaPropagaExcecaoAoLerContextoDeSeguranca() {
        // Um Authentication cujo getName() lanca simula uma falha inesperada
        // na leitura do contexto - a auditoria deve engolir e nao propagar.
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken("x", "y") {
            @Override
            public String getName() {
                throw new RuntimeException("falha inesperada");
            }
        });
        SecurityContextHolder.setContext(context);

        service.registrar("ACAO_QUALQUER", "detalhe");

        verify(repo, never()).save(any());
    }

    @Test
    void listarDelegaParaRepositorioOrdenadoPorDataHoraDesc() {
        Pageable pageable = PageRequest.of(0, 30);
        LogAuditoria log1 = new LogAuditoria("admin", "LOGIN", "ok");
        Page<LogAuditoria> pagina = new PageImpl<>(List.of(log1), pageable, 1);
        when(repo.findAllByOrderByDataHoraDesc(pageable)).thenReturn(pagina);

        Page<LogAuditoria> resultado = service.listar(pageable);

        assertThat(resultado.getContent()).containsExactly(log1);
        verify(repo).findAllByOrderByDataHoraDesc(pageable);
    }
}
