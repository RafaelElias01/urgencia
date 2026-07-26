package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.*;
import br.gov.saude.sgpur.repository.SolicitacaoOnlineRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Testes do fluxo de staging do modulo experimental "Solicitacao Online"
 * (ver docs/PLANO-SOLICITANTE.md). Cobre as regras que este servico impoe:
 * equipe/e-mail SEMPRE vem do usuario logado (nunca do formulario), e as
 * transicoes de status (ENVIADA -> CANCELADA/DEVOLVIDA/CONVERTIDA) so valem
 * a partir de ENVIADA.
 */
@ExtendWith(MockitoExtension.class)
class SolicitacaoOnlineServiceTest {

    @Mock
    SolicitacaoOnlineRepository repository;
    @Mock
    AnexoSolicitacaoOnlineStorageService anexoStorage;
    @Mock
    AnexoStorageService anexoStorageProcesso;
    @Mock
    UsuarioRepository usuarioRepository;
    @Mock
    EmailSenderService emailSenderService;

    SolicitacaoOnlineService service;

    @BeforeEach
    void setUp() {
        service = new SolicitacaoOnlineService(repository, anexoStorage, anexoStorageProcesso,
            usuarioRepository, emailSenderService, "http://localhost:3000");
    }

    private Usuario usuarioSolicitante(Long id) {
        Usuario u = new Usuario();
        u.setId(id);
        u.setUsername("solicitante" + id);
        u.setPerfil(Perfil.SOLICITANTE);
        u.setEquipeSolicitante("HCPA");
        u.setEmail("hcpa@example.com");
        return u;
    }

    private SolicitacaoOnline solicitacaoPedido() {
        SolicitacaoOnline s = new SolicitacaoOnline();
        s.setPacienteNome("Fulano de Tal");
        s.setPacienteRgct("123456789-12345");
        s.setDataSituacaoEspecial(LocalDate.now());
        s.setJustificativaClinica("Quadro grave, necessita avaliacao urgente.");
        return s;
    }

    @Test
    void criarPreencheEquipeEEmailAPartirDoUsuarioLogadoNuncaDoFormulario() {
        when(repository.save(any(SolicitacaoOnline.class))).thenAnswer(inv -> inv.getArgument(0));
        Usuario usuario = usuarioSolicitante(1L);
        SolicitacaoOnline pedido = solicitacaoPedido();
        // Tentativa de forjar outra equipe no formulario - deve ser ignorada.
        pedido.setSolicitanteEquipe("EQUIPE FORJADA");
        pedido.setSolicitanteEmail("forjado@example.com");

        SolicitacaoOnline salva = service.criar(pedido, usuario, null);

        assertThat(salva.getSolicitanteEquipe()).isEqualTo("HCPA");
        assertThat(salva.getSolicitanteEmail()).isEqualTo("hcpa@example.com");
        assertThat(salva.getUsuarioSolicitante()).isSameAs(usuario);
        assertThat(salva.getStatus()).isEqualTo(StatusSolicitacaoOnline.ENVIADA);
    }

    @Test
    void criarSobrescreveDataEnvioForjadaNoFormularioComOMomentoRealDoEnvio() {
        when(repository.save(any(SolicitacaoOnline.class))).thenAnswer(inv -> inv.getArgument(0));
        Usuario usuario = usuarioSolicitante(1L);
        SolicitacaoOnline pedido = solicitacaoPedido();
        // Tentativa de forjar uma data de envio antiga (ex.: para furar a fila
        // de triagem, que ordena por dataEnvio ASC) - deve ser sobrescrita.
        LocalDateTime dataForjada = LocalDateTime.now().minusYears(1);
        pedido.setDataEnvio(dataForjada);

        LocalDateTime antes = LocalDateTime.now();
        SolicitacaoOnline salva = service.criar(pedido, usuario, null);
        LocalDateTime depois = LocalDateTime.now();

        assertThat(salva.getDataEnvio()).isNotEqualTo(dataForjada);
        assertThat(salva.getDataEnvio()).isBetween(antes, depois);
    }

    @Test
    void criarComUsuarioSemEquipeVinculadaLancaExcecao() {
        Usuario usuario = usuarioSolicitante(1L);
        usuario.setEquipeSolicitante(null);

        assertThatThrownBy(() -> service.criar(solicitacaoPedido(), usuario, null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("sem equipe vinculada");
    }

    @Test
    void cancelarPorOutroUsuarioLancaExcecao() {
        SolicitacaoOnline s = solicitacaoPedido();
        s.setId(10L);
        s.setUsuarioSolicitante(usuarioSolicitante(1L));
        s.setStatus(StatusSolicitacaoOnline.ENVIADA);
        when(repository.findById(10L)).thenReturn(java.util.Optional.of(s));

        assertThatThrownBy(() -> service.cancelar(10L, 2L))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("proprias solicitacoes");
    }

    @Test
    void cancelarSolicitacaoJaTriadaLancaExcecao() {
        SolicitacaoOnline s = solicitacaoPedido();
        s.setId(11L);
        s.setUsuarioSolicitante(usuarioSolicitante(1L));
        s.setStatus(StatusSolicitacaoOnline.CONVERTIDA);
        when(repository.findById(11L)).thenReturn(java.util.Optional.of(s));

        assertThatThrownBy(() -> service.cancelar(11L, 1L))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("nao foram triadas");
    }

    @Test
    void cancelarPeloProprioDonoEnquantoEnviadaMarcaCancelada() {
        SolicitacaoOnline s = solicitacaoPedido();
        s.setId(12L);
        s.setUsuarioSolicitante(usuarioSolicitante(1L));
        s.setStatus(StatusSolicitacaoOnline.ENVIADA);
        when(repository.findById(12L)).thenReturn(java.util.Optional.of(s));

        service.cancelar(12L, 1L);

        assertThat(s.getStatus()).isEqualTo(StatusSolicitacaoOnline.CANCELADA);
    }

    @Test
    void devolverRegistraObservacoesEStatusDevolvida() {
        SolicitacaoOnline s = solicitacaoPedido();
        s.setId(13L);
        s.setStatus(StatusSolicitacaoOnline.ENVIADA);
        when(repository.findById(13L)).thenReturn(java.util.Optional.of(s));

        service.devolver(13L, "Falta documento clinico.");

        assertThat(s.getStatus()).isEqualTo(StatusSolicitacaoOnline.DEVOLVIDA);
        assertThat(s.getObservacoesTriagem()).isEqualTo("Falta documento clinico.");
    }

    @Test
    void devolverSolicitacaoJaTriadaLancaExcecao() {
        SolicitacaoOnline s = solicitacaoPedido();
        s.setId(14L);
        s.setStatus(StatusSolicitacaoOnline.DEVOLVIDA);
        when(repository.findById(14L)).thenReturn(java.util.Optional.of(s));

        assertThatThrownBy(() -> service.devolver(14L, "motivo"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void converterSemAnexosMarcaConvertidaEVinculaProcessoGerado() {
        SolicitacaoOnline s = solicitacaoPedido();
        s.setId(15L);
        s.setStatus(StatusSolicitacaoOnline.ENVIADA);
        when(repository.findById(15L)).thenReturn(java.util.Optional.of(s));
        Processo processo = new Processo();
        processo.setId(99L);
        processo.setNumero("01/2026");

        service.converter(15L, processo);

        assertThat(s.getStatus()).isEqualTo(StatusSolicitacaoOnline.CONVERTIDA);
        assertThat(s.getProcessoGerado()).isSameAs(processo);
    }

    @Test
    void converterSolicitacaoJaTriadaLancaExcecao() {
        SolicitacaoOnline s = solicitacaoPedido();
        s.setId(16L);
        s.setStatus(StatusSolicitacaoOnline.CANCELADA);
        when(repository.findById(16L)).thenReturn(java.util.Optional.of(s));

        assertThatThrownBy(() -> service.converter(16L, new Processo()))
            .isInstanceOf(IllegalStateException.class);
    }
}
