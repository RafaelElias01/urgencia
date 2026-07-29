package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.ControleUrgencia;
import br.gov.saude.sgpur.domain.SituacaoUrgencia;
import br.gov.saude.sgpur.repository.ControleUrgenciaRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Testes do ControleUrgenciaService: criacao (com vencimento automatico
 * hoje+30), renovacao, cancelamento, atualizacao de dados descritivos e as
 * consultas de listagem.
 */
@ExtendWith(MockitoExtension.class)
class ControleUrgenciaServiceTest {

    @Mock
    private ControleUrgenciaRepository repo;

    private ControleUrgenciaService service;

    private ControleUrgencia registro(Long id) {
        ControleUrgencia c = new ControleUrgencia("Maria", "RGCT1", "Equipe A", "O+",
            SituacaoUrgencia.ATIVA, LocalDate.now().plusDays(30));
        c.setId(id);
        return c;
    }

    @Test
    void criarDefineSituacaoAtivaEVencimentoAutomaticoQuandoAusente() {
        service = new ControleUrgenciaService(repo);
        ControleUrgencia novo = new ControleUrgencia("Joao", "RGCT2", "Equipe B", "A+", null, null);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ControleUrgencia salvo = service.criar(novo);

        assertThat(salvo.getSituacao()).isEqualTo(SituacaoUrgencia.ATIVA);
        assertThat(salvo.getDataVencimento()).isEqualTo(LocalDate.now().plusDays(30));
    }

    @Test
    void criarRespeitaVencimentoJaInformado() {
        service = new ControleUrgenciaService(repo);
        LocalDate vencimentoManual = LocalDate.now().plusDays(10);
        ControleUrgencia novo = new ControleUrgencia("Joao", "RGCT2", "Equipe B", "A+", null, vencimentoManual);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ControleUrgencia salvo = service.criar(novo);

        assertThat(salvo.getDataVencimento()).isEqualTo(vencimentoManual);
    }

    @Test
    void renovarAvancaVencimentoEMarcaComoRenovada() {
        service = new ControleUrgenciaService(repo);
        ControleUrgencia c = registro(1L);
        when(repo.findById(1L)).thenReturn(Optional.of(c));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ControleUrgencia renovado = service.renovar(1L);

        assertThat(renovado.getSituacao()).isEqualTo(SituacaoUrgencia.RENOVADA);
        assertThat(renovado.getDataUltimaRenovacao()).isEqualTo(LocalDate.now());
        assertThat(renovado.getDataVencimento()).isEqualTo(LocalDate.now().plusDays(30));
    }

    @Test
    void renovarLancaExcecaoQuandoRegistroNaoExiste() {
        service = new ControleUrgenciaService(repo);
        when(repo.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.renovar(99L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("99");
    }

    @Test
    void cancelarMarcaSituacaoCanceladaEGravaObservacoesQuandoInformadas() {
        service = new ControleUrgenciaService(repo);
        ControleUrgencia c = registro(1L);
        when(repo.findById(1L)).thenReturn(Optional.of(c));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ControleUrgencia cancelado = service.cancelar(1L, "Paciente transplantado");

        assertThat(cancelado.getSituacao()).isEqualTo(SituacaoUrgencia.CANCELADA);
        assertThat(cancelado.getObservacoes()).isEqualTo("Paciente transplantado");
    }

    @Test
    void cancelarNaoSobrescreveObservacoesQuandoBranco() {
        service = new ControleUrgenciaService(repo);
        ControleUrgencia c = registro(1L);
        c.setObservacoes("Original");
        when(repo.findById(1L)).thenReturn(Optional.of(c));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ControleUrgencia cancelado = service.cancelar(1L, "   ");

        assertThat(cancelado.getObservacoes()).isEqualTo("Original");
    }

    /**
     * O formulario de edicao envia tambem a data de vencimento - ela precisa
     * ser copiada (correcao de data digitada errada). Ate 2026-07-29 o metodo
     * ignorava esse campo e o operador via "atualizado com sucesso" sem
     * nenhuma alteracao no prazo.
     */
    @Test
    void atualizarCopiaTodosOsCamposDoFormularioInclusiveVencimento() {
        service = new ControleUrgenciaService(repo);
        ControleUrgencia existente = registro(1L);
        when(repo.findById(1L)).thenReturn(Optional.of(existente));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        LocalDate novoVencimento = LocalDate.now().plusDays(97);
        ControleUrgencia dados = new ControleUrgencia("Maria Nova", "RGCT9", "Equipe Nova", "B+",
            null, novoVencimento);
        dados.setId(1L);
        dados.setObservacoes("Obs nova");

        ControleUrgencia atualizado = service.atualizar(dados);

        assertThat(atualizado.getNomePaciente()).isEqualTo("Maria Nova");
        assertThat(atualizado.getRgct()).isEqualTo("RGCT9");
        assertThat(atualizado.getEquipe()).isEqualTo("Equipe Nova");
        assertThat(atualizado.getAbo()).isEqualTo("B+");
        assertThat(atualizado.getObservacoes()).isEqualTo("Obs nova");
        assertThat(atualizado.getDataVencimento()).isEqualTo(novoVencimento);
    }

    /**
     * Situacao e os carimbos de sistema NAO sao copiados de proposito: o
     * formulario nao os envia, entao o objeto vinculado chega com o default
     * ATIVA/true e copiar isso ressuscitaria uma urgencia cancelada a cada
     * correcao de nome.
     */
    @Test
    void atualizarNaoAlteraSituacaoNemCarimbosDeSistema() {
        service = new ControleUrgenciaService(repo);
        ControleUrgencia existente = registro(1L);
        existente.setSituacao(SituacaoUrgencia.CANCELADA);
        existente.setDataUltimaRenovacao(LocalDate.now().minusDays(3));
        existente.setProcessoId(42L);
        when(repo.findById(1L)).thenReturn(Optional.of(existente));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ControleUrgencia dados = new ControleUrgencia("Maria Nova", "RGCT9", "Equipe Nova", "B+",
            SituacaoUrgencia.ATIVA, LocalDate.now().plusDays(5));
        dados.setId(1L);
        dados.setAtivo(true);
        dados.setDataUltimaRenovacao(LocalDate.now());
        dados.setProcessoId(999L);

        ControleUrgencia atualizado = service.atualizar(dados);

        assertThat(atualizado.getSituacao()).isEqualTo(SituacaoUrgencia.CANCELADA);
        assertThat(atualizado.getDataUltimaRenovacao()).isEqualTo(LocalDate.now().minusDays(3));
        assertThat(atualizado.getProcessoId()).isEqualTo(42L);
    }

    @Test
    void atualizarRejeitaVencimentoVazioEmVezDeDescartarEmSilencio() {
        service = new ControleUrgenciaService(repo);
        ControleUrgencia existente = registro(1L);
        when(repo.findById(1L)).thenReturn(Optional.of(existente));

        ControleUrgencia dados = new ControleUrgencia("Maria Nova", "RGCT9", "Equipe Nova", "B+", null, null);
        dados.setId(1L);

        assertThatThrownBy(() -> service.atualizar(dados))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("data de vencimento");
        verify(repo, never()).save(any());
    }

    @Test
    void listarAtivasDelegaAoRepositorio() {
        service = new ControleUrgenciaService(repo);
        List<ControleUrgencia> lista = List.of(registro(1L));
        when(repo.findAllAtivasOrdenadas()).thenReturn(lista);

        assertThat(service.listarAtivas()).isEqualTo(lista);
    }

    @Test
    void listarTodasDelegaAoRepositorio() {
        service = new ControleUrgenciaService(repo);
        List<ControleUrgencia> lista = List.of(registro(1L), registro(2L));
        when(repo.findAll()).thenReturn(lista);

        assertThat(service.listarTodas()).isEqualTo(lista);
    }

    @Test
    void listarAVencerOuVencidasDelegaAoRepositorioComADataInformada() {
        service = new ControleUrgenciaService(repo);
        LocalDate ate = LocalDate.now().plusDays(5);
        List<ControleUrgencia> lista = List.of(registro(1L));
        when(repo.findAVencerOuVencidas(ate)).thenReturn(lista);

        assertThat(service.listarAVencerOuVencidas(ate)).isEqualTo(lista);

        ArgumentCaptor<LocalDate> captor = ArgumentCaptor.forClass(LocalDate.class);
        verify(repo).findAVencerOuVencidas(captor.capture());
        assertThat(captor.getValue()).isEqualTo(ate);
    }

    @Test
    void buscarPorIdLancaExcecaoQuandoNaoExiste() {
        service = new ControleUrgenciaService(repo);
        when(repo.findById(5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.buscarPorId(5L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("5");
    }

    @Test
    void contarPorSituacaoDelegaAoRepositorio() {
        service = new ControleUrgenciaService(repo);
        when(repo.countBySituacao(SituacaoUrgencia.ATIVA)).thenReturn(4L);

        assertThat(service.contarPorSituacao(SituacaoUrgencia.ATIVA)).isEqualTo(4L);
    }
}
