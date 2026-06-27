package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.Parecer;
import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.StatusProcesso;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Gera textos de e-mail prontos (copiar/colar) para cada etapa do processo,
 * pre-preenchidos com os dados do processo. No e-mail aos medicos os dados
 * pessoais do paciente sao OCULTADOS (so vai o necessario para analise clinica).
 */
@Service
public class EmailTemplateService {

    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    public List<EmailTemplate> gerar(Processo p) {
        List<EmailTemplate> lista = new ArrayList<>();
        lista.add(emailMedicos(p));
        if (p.getStatus() == StatusProcesso.DEFERIDO) {
            lista.add(emailDeferido(p));
        } else if (p.getStatus() == StatusProcesso.INDEFERIDO) {
            lista.add(emailIndeferido(p));
        }
        return lista;
    }

    /** E-mail de solicitacao de parecer aos medicos - SEM dados pessoais do paciente. */
    private EmailTemplate emailMedicos(Processo p) {
        String medicos = p.getPareceres().stream()
            .map(Parecer::getMembro)
            .map(m -> "- " + m.getRotulo())
            .collect(Collectors.joining("\n"));
        String data = p.getDataSituacaoEspecial() != null
            ? p.getDataSituacaoEspecial().format(DATA) : "(data)";

        String corpo = """
            Prezados(as) avaliadores(as),

            Encaminhamos para parecer da Urgencia Renal o processo abaixo. Solicitamos
            a analise clinica e o retorno do parecer (Favoravel / Nao favoravel /
            Solicita informacao).

            Processo: %s
            Data da situacao especial: %s

            (Os dados pessoais do paciente foram omitidos. Em caso de necessidade de
            identificacao para a analise, solicitar a Secretaria.)

            Avaliadores designados:
            %s

            Atenciosamente,
            Equipe de Urgencia Renal - Secretaria de Saude
            """.formatted(p.getNumero(), data, medicos);

        return new EmailTemplate("medicos", "Envio aos medicos (parecer)", "send",
            "Urgencia Renal - Solicitacao de parecer - Processo " + p.getNumero(), corpo);
    }

    private EmailTemplate emailDeferido(Processo p) {
        String corpo = """
            Prezados(as),

            Informamos que o processo de Urgencia Renal %s, referente ao paciente
            %s, foi DEFERIDO pela equipe de Urgencia Renal.

            Equipe solicitante: %s

            Permanecemos a disposicao para esclarecimentos.

            Atenciosamente,
            Equipe de Urgencia Renal - Secretaria de Saude
            """.formatted(p.getNumero(), p.getPacienteNome(), p.getSolicitanteEquipe());

        return new EmailTemplate("deferido", "Resposta ao solicitante (Deferido)", "check-circle",
            "Urgencia Renal - Processo " + p.getNumero() + " - DEFERIDO", corpo);
    }

    private EmailTemplate emailIndeferido(Processo p) {
        String motivo = (p.getMotivoIndeferimento() == null || p.getMotivoIndeferimento().isBlank())
            ? "(informar o motivo do indeferimento)" : p.getMotivoIndeferimento();

        String corpo = """
            Prezados(as),

            Informamos que o processo de Urgencia Renal %s, referente ao paciente
            %s, foi INDEFERIDO pela equipe de Urgencia Renal.

            Equipe solicitante: %s
            Motivo: %s

            O oficio de indeferimento segue anexo a este e-mail.

            Atenciosamente,
            Equipe de Urgencia Renal - Secretaria de Saude
            """.formatted(p.getNumero(), p.getPacienteNome(), p.getSolicitanteEquipe(), motivo);

        return new EmailTemplate("indeferido", "Resposta ao solicitante (Indeferido)", "x-circle",
            "Urgencia Renal - Processo " + p.getNumero() + " - INDEFERIDO", corpo);
    }
}
