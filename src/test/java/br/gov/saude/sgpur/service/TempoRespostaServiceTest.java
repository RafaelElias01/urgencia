package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.domain.Parecer;
import br.gov.saude.sgpur.domain.ResultadoParecer;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.service.TempoRespostaService.ResumoTempo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Cobre o calculo de tempo de resposta dos avaliadores em dias corridos:
 * media geral, media por membro, contagem "fora do prazo", caso sem respostas
 * (media null) e o descarte defensivo de dias negativos.
 */
@ExtendWith(MockitoExtension.class)
class TempoRespostaServiceTest {

    @Mock private ParecerRepository parecerRepo;

    private TempoRespostaService service(int prazoDias) {
        return new TempoRespostaService(parecerRepo, prazoDias);
    }

    private MembroUrgenciaRenal membro(long id) {
        MembroUrgenciaRenal m = new MembroUrgenciaRenal();
        m.setId(id);
        return m;
    }

    private Parecer parecer(MembroUrgenciaRenal m, LocalDate envio, LocalDate resposta) {
        Parecer p = new Parecer();
        p.setMembro(m);
        p.setResultado(ResultadoParecer.FAVORAVEL);
        p.setDataEnvio(envio);
        p.setDataResposta(resposta);
        return p;
    }

    @Test
    void mediaGeralEPorMembroEmDiasCorridos() {
        MembroUrgenciaRenal m1 = membro(1L);
        MembroUrgenciaRenal m2 = membro(2L);
        // m1: 2 dias e 4 dias -> media 3 ; m2: 10 dias
        when(parecerRepo.findRespondidosComDatas()).thenReturn(List.of(
            parecer(m1, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 3)),  // 2
            parecer(m1, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 5)),  // 4
            parecer(m2, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 11))  // 10
        ));

        ResumoTempo r = service(7).calcular();

        assertThat(r.totalAvaliados()).isEqualTo(3);
        assertThat(r.mediaGeralDias()).isEqualTo((2 + 4 + 10) / 3.0);
        assertThat(r.prazoDias()).isEqualTo(7);
        // apenas o de 10 dias esta acima do prazo de 7
        assertThat(r.foraDoPrazo()).isEqualTo(1);

        assertThat(r.porMembro().get(1L).avaliados()).isEqualTo(2);
        assertThat(r.porMembro().get(1L).mediaDias()).isEqualTo(3.0);
        assertThat(r.porMembro().get(1L).foraDoPrazo()).isZero();

        assertThat(r.porMembro().get(2L).avaliados()).isEqualTo(1);
        assertThat(r.porMembro().get(2L).mediaDias()).isEqualTo(10.0);
        assertThat(r.porMembro().get(2L).foraDoPrazo()).isEqualTo(1);
    }

    @Test
    void semRespostasMediaNula() {
        when(parecerRepo.findRespondidosComDatas()).thenReturn(List.of());

        ResumoTempo r = service(7).calcular();

        assertThat(r.totalAvaliados()).isZero();
        assertThat(r.mediaGeralDias()).isNull();
        assertThat(r.foraDoPrazo()).isZero();
        assertThat(r.porMembro()).isEmpty();
        assertThat(TempoRespostaService.formatarDias(r.mediaGeralDias())).isEqualTo("—");
    }

    @Test
    void descartaDiasNegativos() {
        MembroUrgenciaRenal m1 = membro(1L);
        when(parecerRepo.findRespondidosComDatas()).thenReturn(List.of(
            parecer(m1, LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 1)),  // -4, descartado
            parecer(m1, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 3))   // 2
        ));

        ResumoTempo r = service(7).calcular();

        assertThat(r.totalAvaliados()).isEqualTo(1);
        assertThat(r.mediaGeralDias()).isEqualTo(2.0);
    }

    @Test
    void formatarDiasPluralizaEUsaVirgula() {
        assertThat(TempoRespostaService.formatarDias(null)).isEqualTo("—");
        assertThat(TempoRespostaService.formatarDias(0.0)).isEqualTo("0 dias");
        assertThat(TempoRespostaService.formatarDias(1.0)).isEqualTo("1 dia");
        assertThat(TempoRespostaService.formatarDias(3.5)).isEqualTo("3,5 dias");
    }
}
