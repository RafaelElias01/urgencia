package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.*;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.ProcessoRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
        long id = 1;
        for (ResultadoParecer r : resultados) {
            Parecer par = parecer(r);
            par.setId(id++);
            p.addParecer(par);
        }
        return p;
    }

    /** Vincula um anexo de RESPOSTA_AVALIADOR ao parecer informado. */
    private void anexarResposta(Processo p, Parecer parecer) {
        Anexo a = new Anexo();
        a.setTipo(TipoAnexo.RESPOSTA_AVALIADOR);
        a.setParecer(parecer);
        p.addAnexo(a);
    }

    /** Anexa a resposta de todos os pareceres ja recebidos (resultado != null). */
    private void anexarRespostasParaTodosRecebidos(Processo p) {
        p.getPareceres().stream()
            .filter(par -> par.getResultado() != null)
            .forEach(par -> anexarResposta(p, par));
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

    @Test
    void processoNasceSolicitado() {
        assertThat(new Processo().getStatus()).isEqualTo(StatusProcesso.SOLICITADO);
    }

    @Test
    void registrarEnvioMudaParaEnviado() {
        Processo p = new Processo();
        when(processoRepository.findById(1L)).thenReturn(java.util.Optional.of(p));
        when(processoRepository.save(p)).thenReturn(p);
        service.registrarEnvio(1L);
        assertThat(p.getStatus()).isEqualTo(StatusProcesso.ENVIADO);
    }

    @Test
    void atualizarStatusVaiParaSolicitaInformacaoQuandoMedicoPedeInfo() {
        Processo p = comPareceres(ResultadoParecer.FAVORAVEL,
            ResultadoParecer.SOLICITA_INFORMACAO, null);
        when(processoRepository.findById(2L)).thenReturn(java.util.Optional.of(p));
        when(processoRepository.save(p)).thenReturn(p);
        service.atualizarStatusPorPareceres(2L);
        assertThat(p.getStatus()).isEqualTo(StatusProcesso.SOLICITA_INFORMACAO);
    }

    @Test
    void atualizarStatusNaoRebaixaProcessoJaDecidido() {
        Processo p = comPareceres(ResultadoParecer.SOLICITA_INFORMACAO);
        p.setStatus(StatusProcesso.DEFERIDO);
        when(processoRepository.findById(3L)).thenReturn(java.util.Optional.of(p));
        service.atualizarStatusPorPareceres(3L);
        assertThat(p.getStatus()).isEqualTo(StatusProcesso.DEFERIDO);
    }

    @Test
    void decidirDeferidoExigeNoMinimoDoisFavoraveis() {
        Processo p = comPareceres(ResultadoParecer.FAVORAVEL,
            ResultadoParecer.NAO_FAVORAVEL, ResultadoParecer.NAO_FAVORAVEL);
        when(processoRepository.findById(4L)).thenReturn(java.util.Optional.of(p));
        assertThatThrownBy(() -> service.decidir(4L, StatusProcesso.DEFERIDO, null))
            .isInstanceOf(IllegalStateException.class);
        assertThat(p.getStatus()).isNotEqualTo(StatusProcesso.DEFERIDO);
    }

    @Test
    void decidirDeferidoComDoisFavoraveis() {
        Processo p = comPareceres(ResultadoParecer.FAVORAVEL,
            ResultadoParecer.FAVORAVEL, ResultadoParecer.NAO_FAVORAVEL);
        anexarRespostasParaTodosRecebidos(p);
        when(processoRepository.findById(5L)).thenReturn(java.util.Optional.of(p));
        when(processoRepository.save(p)).thenReturn(p);
        service.decidir(5L, StatusProcesso.DEFERIDO, null);
        assertThat(p.getStatus()).isEqualTo(StatusProcesso.DEFERIDO);
    }

    @Test
    void decidirIndeferidoExigeNoMinimoDoisDesfavoraveis() {
        Processo p = comPareceres(ResultadoParecer.FAVORAVEL,
            ResultadoParecer.FAVORAVEL, ResultadoParecer.NAO_FAVORAVEL);
        when(processoRepository.findById(6L)).thenReturn(java.util.Optional.of(p));
        assertThatThrownBy(() -> service.decidir(6L, StatusProcesso.INDEFERIDO, "motivo"))
            .isInstanceOf(IllegalStateException.class);
        assertThat(p.getStatus()).isNotEqualTo(StatusProcesso.INDEFERIDO);
    }

    @Test
    void decidirIndeferidoComDoisDesfavoraveis() {
        Processo p = comPareceres(ResultadoParecer.NAO_FAVORAVEL,
            ResultadoParecer.NAO_FAVORAVEL, ResultadoParecer.FAVORAVEL);
        anexarRespostasParaTodosRecebidos(p);
        when(processoRepository.findById(7L)).thenReturn(java.util.Optional.of(p));
        when(processoRepository.save(p)).thenReturn(p);
        service.decidir(7L, StatusProcesso.INDEFERIDO, "motivo");
        assertThat(p.getStatus()).isEqualTo(StatusProcesso.INDEFERIDO);
    }

    @Test
    void decidirBloqueiaQuandoRespostaRecebidaSemAnexo() {
        // 2 favoraveis recebidos, mas sem o anexo da resposta -> nao pode deferir
        Processo p = comPareceres(ResultadoParecer.FAVORAVEL,
            ResultadoParecer.FAVORAVEL, ResultadoParecer.NAO_FAVORAVEL);
        when(processoRepository.findById(8L)).thenReturn(java.util.Optional.of(p));
        assertThatThrownBy(() -> service.decidir(8L, StatusProcesso.DEFERIDO, null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Anexe a resposta");
        assertThat(p.getStatus()).isNotEqualTo(StatusProcesso.DEFERIDO);
    }

    @Test
    void pareceresRecebidosSemAnexoListaApenasOsFaltantes() {
        Processo p = comPareceres(ResultadoParecer.FAVORAVEL,
            ResultadoParecer.FAVORAVEL, null);
        // anexa a resposta apenas do primeiro parecer recebido
        Parecer primeiro = p.getPareceres().get(0);
        anexarResposta(p, primeiro);
        var faltantes = service.pareceresRecebidosSemAnexo(p);
        assertThat(faltantes).containsExactly(p.getPareceres().get(1));
    }
}
