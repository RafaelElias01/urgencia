package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.config.EmailProperties;
import br.gov.saude.sgpur.domain.*;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class EmailTemplateServiceTest {

    private final EmailTemplateService service = new EmailTemplateService(new EmailProperties());

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
        Processo p = processo();
        p.getPareceres().forEach(par -> par.setDataEnvio(LocalDate.now()));
        EmailTemplate medicos = service.gerar(p).stream()
            .filter(e -> e.chave().equals("medicos")).findFirst().orElseThrow();

        // Imparcialidade: nome e RGCT do paciente NAO podem aparecer no e-mail aos avaliadores
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
    void emailDeferidoMencionaComprovanteSntEmAnexo() {
        Processo p = processo();
        p.setStatus(StatusProcesso.DEFERIDO);
        EmailTemplate deferido = service.gerar(p).stream()
            .filter(e -> e.chave().equals("deferido")).findFirst().orElseThrow();
        assertThat(deferido.corpo()).contains("EM ANEXO");
        assertThat(deferido.corpo()).contains("Sistema Nacional de Transplantes");
    }

    @Test
    void emailSolicitaInfoLevaNomeCompletoAoSolicitante() {
        Processo p = processo();
        p.setStatus(StatusProcesso.SOLICITA_INFORMACAO);
        EmailTemplate info = service.gerar(p).stream()
            .filter(e -> e.chave().equals("solicita-info")).findFirst().orElseThrow();
        // E-mail dirigido a EQUIPE SOLICITANTE: DEVE conter o nome completo do paciente
        assertThat(info.corpo()).contains("Joao Paciente Secreto");
        assertThat(info.assunto()).contains("Joao Paciente Secreto");
        assertThat(info.corpo()).contains("07/2026");
    }

    @Test
    void emAnaliseNaoGeraEmailDeResposta() {
        Processo p = processo(); // EM_ANALISE por padrao
        p.getPareceres().forEach(par -> par.setDataEnvio(LocalDate.now()));
        long respostas = service.gerar(p).stream()
            .filter(e -> e.chave().equals("deferido") || e.chave().equals("indeferido")).count();
        assertThat(respostas).isZero();
    }

    @Test
    void lembreteAvaliadorNaoExpoeNomeDoPacienteEAvisaSobreAvaliacaoPendente() {
        Processo p = processo();
        MembroUrgenciaRenal membro = new MembroUrgenciaRenal("HCPA", "Dra. Avaliadora", "avaliadora@example.com");
        EmailTemplate lembrete = service.emailLembreteAvaliador(p, membro);

        // Imparcialidade: nome completo do paciente NUNCA aparece no lembrete ao avaliador
        assertThat(lembrete.corpo()).doesNotContain("Joao Paciente Secreto");
        // Deve conter o numero do processo, o texto de disponibilidade para avaliacao
        // e o nome do avaliador destinatario
        assertThat(lembrete.corpo()).contains("07/2026");
        assertThat(lembrete.corpo()).contains("esta disponivel para sua avaliacao");
        assertThat(lembrete.corpo()).contains("Dra. Avaliadora");
        assertThat(lembrete.assunto()).contains("07/2026");
    }
}
