package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.*;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class EmailTemplateServiceTest {

    private final EmailTemplateService service = new EmailTemplateService();

    private Processo processo() {
        Processo p = new Processo();
        p.setNumero("07/2026");
        p.setPacienteNome("Joao Paciente Secreto");
        p.setPacienteRgct("123456-4360");
        p.setSolicitanteEquipe("Hospital Solicitante");
        p.setDataSituacaoEspecial(LocalDate.of(2026, 6, 1));
        p.addParecer(new Parecer(new MembroUrgenciaRenal("HCPA", "Dr. Avaliador", null)));
        return p;
    }

    @Test
    void emailAosMedicosNaoExpoeDadosDoPaciente() {
        EmailTemplate medicos = service.gerar(processo()).stream()
            .filter(e -> e.chave().equals("medicos")).findFirst().orElseThrow();

        // LGPD: nome e RGCT do paciente NAO podem aparecer no e-mail aos medicos
        assertThat(medicos.corpo()).doesNotContain("Joao Paciente Secreto");
        assertThat(medicos.corpo()).doesNotContain("123456-4360");
        // mas deve trazer o numero do processo e o avaliador
        assertThat(medicos.corpo()).contains("07/2026");
        assertThat(medicos.corpo()).contains("Dr. Avaliador");
    }

    @Test
    void deferidoGeraEmailDeRespostaAoSolicitante() {
        Processo p = processo();
        p.setStatus(StatusProcesso.DEFERIDO);
        boolean temDeferido = service.gerar(p).stream().anyMatch(e -> e.chave().equals("deferido"));
        assertThat(temDeferido).isTrue();
    }

    @Test
    void emAnaliseNaoGeraEmailDeResposta() {
        Processo p = processo(); // EM_ANALISE por padrao
        long respostas = service.gerar(p).stream()
            .filter(e -> e.chave().equals("deferido") || e.chave().equals("indeferido")).count();
        assertThat(respostas).isZero();
    }
}
