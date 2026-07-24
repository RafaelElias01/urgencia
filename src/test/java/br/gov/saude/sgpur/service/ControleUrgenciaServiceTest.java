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

    @Test
    void atualizarCopiaCamposDescritivosSemAlterarSituacaoOuVencimento() {
        service = new ControleUrgenciaService(repo);
        ControleUrgencia existente = registro(1L);
        existente.setSituacao(SituacaoUrgencia.RENOVADA);
        LocalDate vencimentoOriginal = existente.getDataVencimento();
        when(repo.findById(1L)).thenReturn(Optional.of(existente));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ControleUrgencia dados = new ControleUrgencia("Maria Nova", "RGCT9", "Equipe Nova", "B+", null, null);
        dados.setId(1L);
        dados.setObservacoes("Obs nova");

        ControleUrgencia atualizado = service.atualizar(dados);

        assertThat(atualizado.getNomePaciente()).isEqualTo("Maria Nova");
        assertThat(atualizado.getRgct()).isEqualTo("RGCT9");
        assertThat(atualizado.getEquipe()).isEqualTo("Equipe Nova");
        assertThat(atualizado.getAbo()).isEqualTo("B+");
        assertThat(atualizado.getObservacoes()).isEqualTo("Obs nova");
        assertThat(atualizado.getSituacao()).isEqualTo(SituacaoUrgencia.RENOVADA);
        assertThat(atualizado.getDataVencimento()).isEqualTo(vencimentoOriginal);
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
