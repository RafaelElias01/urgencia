package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.*;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.ProcessoRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProcessoServiceTest {

    @Mock
    ProcessoRepository processoRepository;
    @Mock
    MembroUrgenciaRenalRepository membroRepository;
    @InjectMocks
    ProcessoService service;

    private Parecer parecer(ResultadoParecer r) {
        Parecer p = new Parecer(new MembroUrgenciaRenal("HCPA", "Medico", null));
        p.setResultado(r);
        return p;
    }

    private Processo comPareceres(ResultadoParecer... resultados) {
        Processo p = new Processo();
        for (ResultadoParecer r : resultados) {
            p.addParecer(parecer(r));
        }
        return p;
    }

    @Test
    void defereComDoisFavoraveis() {
        Processo p = comPareceres(ResultadoParecer.FAVORAVEL, ResultadoParecer.FAVORAVEL, null);
        assertThat(service.contarFavoraveis(p)).isEqualTo(2);
        assertThat(service.sugerirDecisao(p)).contains(StatusProcesso.DEFERIDO);
    }

    @Test
    void indefereQuandoTodosResponderamSemMaioriaFavoravel() {
        Processo p = comPareceres(ResultadoParecer.FAVORAVEL,
            ResultadoParecer.NAO_FAVORAVEL, ResultadoParecer.NAO_FAVORAVEL);
        assertThat(service.sugerirDecisao(p)).contains(StatusProcesso.INDEFERIDO);
    }

    @Test
    void semSugestaoQuandoFaltamRespostas() {
        Processo p = comPareceres(ResultadoParecer.FAVORAVEL, null, null);
        assertThat(service.sugerirDecisao(p)).isEmpty();
    }

    @Test
    void numeracaoManualEm2026EAutomaticaEm2027() {
        assertThat(service.isNumeracaoAutomatica(2026)).isFalse();
        assertThat(service.isNumeracaoAutomatica(2027)).isTrue();
    }

    @Test
    void proximoNumeroFormataSequencialDoAno() {
        when(processoRepository.findMaxSequencialByAno(2027)).thenReturn(4);
        assertThat(service.proximoNumero(2027)).isEqualTo("05/2027");
    }

    @Test
    void proximoNumeroComecaEmUmQuandoAnoVazio() {
        when(processoRepository.findMaxSequencialByAno(2027)).thenReturn(null);
        assertThat(service.proximoNumero(2027)).isEqualTo("01/2027");
    }

    @Test
    void contarRespondidosIgnoraPendentes() {
        Processo p = comPareceres(ResultadoParecer.FAVORAVEL, ResultadoParecer.SEM_RESPOSTA, null);
        assertThat(service.contarRespondidos(p)).isEqualTo(2);
    }
}
