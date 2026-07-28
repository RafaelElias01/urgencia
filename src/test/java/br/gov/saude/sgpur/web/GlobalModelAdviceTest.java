package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.domain.Parecer;
import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.StatusProcesso;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import br.gov.saude.sgpur.service.MensagemSolicitacaoService;
import br.gov.saude.sgpur.service.SolicitacaoOnlineService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Testes do GlobalModelAdvice: o contador "pendentesAvaliador" do badge da
 * navbar so e calculado para usuarios autenticados com ROLE_AVALIADOR e
 * membro vinculado; para os demais casos (nao autenticado, sem o papel, ou
 * avaliador sem membro) deve retornar 0 sem lancar erro.
 */
@ExtendWith(MockitoExtension.class)
class GlobalModelAdviceTest {

    @Mock
    private UsuarioRepository usuarioRepo;
    @Mock
    private ParecerRepository parecerRepo;
    @Mock
    private SolicitacaoOnlineService solicitacaoOnlineService;
    @Mock
    private MensagemSolicitacaoService mensagemService;

    private GlobalModelAdvice advice;

    @BeforeEach
    void montarAdvice() {
        // Nao pode ser inicializado num field initializer: os campos @Mock so
        // sao injetados pelo MockitoExtension DEPOIS que o construtor da
        // classe de teste roda, entao usuarioRepo/parecerRepo ainda estariam
        // null se "advice" fosse montado ali.
        advice = new GlobalModelAdvice(usuarioRepo, parecerRepo, solicitacaoOnlineService, true, mensagemService);
    }

    @AfterEach
    void limparContextoDeSeguranca() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void retornaZeroQuandoNaoAutenticado() {
        SecurityContextHolder.clearContext();

        assertThat(advice.pendentesAvaliador()).isZero();
    }

    @Test
    void retornaZeroParaPerfilOperadorMesmoAutenticado() {
        SecurityContextHolder.getContext().setAuthentication(
            new TestingAuthenticationToken("op1", "senha", "ROLE_OPERADOR"));

        assertThat(advice.pendentesAvaliador()).isZero();
    }

    @Test
    void retornaZeroQuandoAvaliadorSemMembroVinculado() {
        SecurityContextHolder.getContext().setAuthentication(
            new TestingAuthenticationToken("aval1", "senha", "ROLE_AVALIADOR"));
        Usuario usuario = new Usuario();
        usuario.setMembro(null);
        when(usuarioRepo.findByUsername("aval1")).thenReturn(Optional.of(usuario));

        assertThat(advice.pendentesAvaliador()).isZero();
    }

    @Test
    void retornaZeroQuandoUsuarioAutenticadoNaoEncontradoNoBanco() {
        SecurityContextHolder.getContext().setAuthentication(
            new TestingAuthenticationToken("fantasma", "senha", "ROLE_AVALIADOR"));
        when(usuarioRepo.findByUsername("fantasma")).thenReturn(Optional.empty());

        assertThat(advice.pendentesAvaliador()).isZero();
    }

    @Test
    void contaSomentePareceresPendentesDeProcessosAtivosParaVotacao() {
        SecurityContextHolder.getContext().setAuthentication(
            new TestingAuthenticationToken("aval1", "senha", "ROLE_AVALIADOR"));

        MembroUrgenciaRenal membro = new MembroUrgenciaRenal("HCPA", "Dr. Teste", null);
        membro.setId(7L);
        Usuario usuario = new Usuario();
        usuario.setMembro(membro);
        when(usuarioRepo.findByUsername("aval1")).thenReturn(Optional.of(usuario));

        Parecer pendenteEmAnalise = parecerComProcesso(StatusProcesso.EM_ANALISE);
        Parecer pendenteEnviado = parecerComProcesso(StatusProcesso.ENVIADO);
        Parecer pendenteFinalizado = parecerComProcesso(StatusProcesso.DEFERIDO);
        when(parecerRepo.findByMembroIdAndResultadoIsNullAndDataEnvioIsNotNull(7L))
            .thenReturn(List.of(pendenteEmAnalise, pendenteEnviado, pendenteFinalizado));

        assertThat(advice.pendentesAvaliador()).isEqualTo(2);
    }

    @Test
    void pendentesTriagemOnlineRetornaZeroQuandoNaoAutenticado() {
        SecurityContextHolder.clearContext();

        assertThat(advice.pendentesTriagemOnline()).isZero();
    }

    @Test
    void pendentesTriagemOnlineRetornaZeroParaAvaliador() {
        SecurityContextHolder.getContext().setAuthentication(
            new TestingAuthenticationToken("aval1", "senha", "ROLE_AVALIADOR"));

        assertThat(advice.pendentesTriagemOnline()).isZero();
    }

    @Test
    void pendentesTriagemOnlineRetornaZeroQuandoModuloDesabilitado() {
        GlobalModelAdvice adviceDesabilitado =
            new GlobalModelAdvice(usuarioRepo, parecerRepo, solicitacaoOnlineService, false, mensagemService);
        SecurityContextHolder.getContext().setAuthentication(
            new TestingAuthenticationToken("op1", "senha", "ROLE_OPERADOR"));

        assertThat(adviceDesabilitado.pendentesTriagemOnline()).isZero();
    }

    @Test
    void pendentesTriagemOnlineContaParaOperador() {
        SecurityContextHolder.getContext().setAuthentication(
            new TestingAuthenticationToken("op1", "senha", "ROLE_OPERADOR"));
        when(solicitacaoOnlineService.contarPendentesTriagem()).thenReturn(3L);

        assertThat(advice.pendentesTriagemOnline()).isEqualTo(3L);
    }

    @Test
    void pendentesTriagemOnlineContaParaAdmin() {
        SecurityContextHolder.getContext().setAuthentication(
            new TestingAuthenticationToken("admin1", "senha", "ROLE_ADMIN"));
        when(solicitacaoOnlineService.contarPendentesTriagem()).thenReturn(5L);

        assertThat(advice.pendentesTriagemOnline()).isEqualTo(5L);
    }

    private Parecer parecerComProcesso(StatusProcesso status) {
        Processo p = new Processo();
        p.setStatus(status);
        MembroUrgenciaRenal membro = new MembroUrgenciaRenal("HCPA", "Dr. Teste", null);
        Parecer par = new Parecer(membro);
        p.addParecer(par);
        return par;
    }
}
