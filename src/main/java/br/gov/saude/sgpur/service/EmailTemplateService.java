package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.config.EmailProperties;
import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.StatusProcesso;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Gera textos de e-mail prontos (copiar/colar) para cada etapa do processo,
 * pre-preenchidos com os dados do processo. No e-mail aos MEDICOS AVALIADORES o
 * nome do paciente e OCULTADO (so iniciais), para preservar a IMPARCIALIDADE do
 * julgamento - os avaliadores decidem sem saber quem e o paciente (convencao da
 * equipe de Urgencia Renal). Os e-mails dirigidos a equipe SOLICITANTE (pedido de
 * informacao complementar, resposta de Deferido/Indeferido) levam o NOME COMPLETO.
 */
@Service
public class EmailTemplateService {

    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** URL base da aplicacao, usada nos links do Portal do Avaliador. */
    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    private final EmailProperties emailProperties;

    public EmailTemplateService(EmailProperties emailProperties) {
        this.emailProperties = emailProperties;
    }

    private String assinatura() {
        return emailProperties.getAssinatura();
    }

    private String assunto(String resto) {
        return emailProperties.getPrefixoAssunto() + " - " + resto;
    }

    public List<EmailTemplate> gerar(Processo p) {
        List<EmailTemplate> lista = new ArrayList<>();
        lista.add(emailMedicos(p));
        // Template de convite ao portal para processos em andamento (ENVIADO/EM_ANALISE)
        if (p.getStatus() == StatusProcesso.ENVIADO || p.getStatus() == StatusProcesso.EM_ANALISE) {
            lista.add(emailConvitePortal(p));
        }
        if (p.getStatus() == StatusProcesso.DEFERIDO) {
            lista.add(emailDeferido(p));
        } else if (p.getStatus() == StatusProcesso.INDEFERIDO) {
            lista.add(emailIndeferido(p));
        } else if (p.getStatus() == StatusProcesso.SOLICITA_INFORMACAO) {
            lista.add(emailSolicitaInfo(p));
        }
        // Filtra templates que so devem ser exibidos apos o envio aos medicos
        // (dataEnvio registrada em ao menos um parecer). Templates com
        // requerEnvio=false sao sempre exibidos.
        boolean envioRealizado = p.getPareceres() != null
            && !p.getPareceres().isEmpty()
            && p.getPareceres().get(0).getDataEnvio() != null;
        return lista.stream()
            .filter(t -> !t.requerEnvio() || envioRealizado)
            .toList();
    }

    /**
     * E-mail de solicitacao de parecer aos medicos avaliadores - SEM o nome do
     * paciente (so iniciais), para preservar a imparcialidade do julgamento.
     */
    private EmailTemplate emailMedicos(Processo p) {
        String medicos = p.getPareceres().stream()
            .map(par -> "- " + par.getMembro().getRotulo())
            .collect(Collectors.joining("\n"));
        String data = p.getDataSituacaoEspecial() != null
            ? p.getDataSituacaoEspecial().format(DATA) : "(data)";
        String iniciais = Iniciais.de(p.getPacienteNome());
        String idProcesso = p.getNumero() + " - Paciente " + iniciais;

        String corpo = """
            Prezados(as) avaliadores(as),

            Encaminhamos para parecer da Urgencia Renal o processo abaixo. Solicitamos
            a analise clinica e o retorno do parecer (Favoravel / Nao favoravel /
            Solicita informacao).

            Processo: %s
            Data da situacao especial: %s

            (O nome do paciente foi omitido para preservar a imparcialidade do
            julgamento; identificado apenas pelas iniciais. Em caso de necessidade
            de identificacao, solicitar a Secretaria.)

            Avaliadores designados:
            %s

            Atenciosamente,
            %s
            """.formatted(idProcesso, data, medicos, assinatura());

        return new EmailTemplate("medicos", "Envio aos medicos (parecer)", "send",
            assunto("Solicitacao de parecer - Processo " + idProcesso), corpo,
            true); // so exibir apos dataEnvio registrada
    }

    /**
     * Convite individual ao avaliador para votar no Portal do Avaliador.
     * Destinado a cada medico separadamente. Contem APENAS iniciais do paciente
     * (imparcialidade) e link de acesso ao portal.
     * Exibido na aba Envio quando o processo esta em ENVIADO/EM_ANALISE.
     */
    public EmailTemplate emailConviteAvaliador(Processo p, MembroUrgenciaRenal membro) {
        String iniciais = Iniciais.de(p.getPacienteNome());
        String idProcesso = p.getNumero() + " - Paciente " + iniciais;
        String portalUrl = baseUrl + "/avaliador";

        String corpo = """
            Prezado(a) %s,

            Voce foi designado(a) como avaliador(a) do processo de Urgencia Renal
            abaixo e pode registrar seu parecer diretamente no sistema da Secretaria,
            sem necessidade de responder por e-mail.

            Processo: %s
            Data da situacao especial: %s

            Para emitir seu parecer, acesse o Portal do Avaliador com suas credenciais:
            %s

            Caso nao possua login, entre em contato com a equipe da Secretaria.

            O nome do paciente foi omitido para preservar a imparcialidade do
            julgamento; identificado apenas pelas iniciais.

            Atenciosamente,
            %s
            """.formatted(
                membro.getNome(),
                idProcesso,
                p.getDataSituacaoEspecial() != null ? p.getDataSituacaoEspecial().format(DATA) : "(data)",
                portalUrl,
                assinatura());

        return new EmailTemplate("convite-avaliador",
            "Convite ao Portal do Avaliador - " + membro.getNome(), "person-check",
            assunto("Solicitacao de avaliacao - Processo " + idProcesso),
            corpo);
    }

    /**
     * Lembrete manual de avaliacao pendente, disparado pelo operador para um
     * avaliador especifico que ainda nao registrou parecer. SEM nome completo
     * do paciente (so iniciais), para preservar a imparcialidade do julgamento.
     */
    public EmailTemplate emailLembreteAvaliador(Processo p, MembroUrgenciaRenal membro) {
        String iniciais = Iniciais.de(p.getPacienteNome());
        String idProcesso = p.getNumero() + " CET-RS - Paciente " + iniciais;
        String portalUrl = baseUrl + "/avaliador";

        String corpo = """
            Prezado(a) %s,

            Lembramos que o processo abaixo permanece disponivel para a sua avaliacao
            e aguarda o seu parecer.

            Processo %s esta disponivel para sua avaliacao.

            Para registrar seu parecer, acesse o Portal do Avaliador com suas credenciais:
            %s

            O nome do paciente foi omitido para preservar a imparcialidade do
            julgamento; identificado apenas pelas iniciais.

            Atenciosamente,
            %s
            """.formatted(membro.getNome(), idProcesso, portalUrl, assinatura());

        return new EmailTemplate("lembrete-avaliador",
            "Lembrete de avaliacao pendente - " + membro.getNome(), "bell",
            assunto("Lembrete de avaliacao pendente - Processo " + idProcesso),
            corpo);
    }

    /**
     * Template agrupado de convite ao portal (exibido na aba Envio do processo).
     * Lista todos os avaliadores com o link unico do portal.
     * SEM nome completo do paciente (so iniciais).
     */
    private EmailTemplate emailConvitePortal(Processo p) {
        String iniciais = Iniciais.de(p.getPacienteNome());
        String idProcesso = p.getNumero() + " - Paciente " + iniciais;
        String portalUrl = baseUrl + "/avaliador";
        String medicos = p.getPareceres().stream()
            .map(par -> "- " + par.getMembro().getNome() + " (" + par.getMembro().getInstituicao() + ")")
            .collect(Collectors.joining("\n"));

        String corpo = """
            Prezados(as) avaliadores(as),

            Voces foram designados(as) para avaliar o processo de Urgencia Renal abaixo
            e podem registrar o parecer diretamente no sistema (sem responder por e-mail).

            Processo: %s
            Data da situacao especial: %s

            Avaliadores designados:
            %s

            Acesse o Portal do Avaliador com suas credenciais:
            %s

            O nome do paciente foi omitido para preservar a imparcialidade do julgamento.

            Atenciosamente,
            %s
            """.formatted(
                idProcesso,
                p.getDataSituacaoEspecial() != null ? p.getDataSituacaoEspecial().format(DATA) : "(data)",
                medicos,
                portalUrl,
                assinatura());

        return new EmailTemplate("convite-portal",
            "Convite ao Portal do Avaliador (votacao no sistema)", "person-check",
            assunto("Acesso ao Portal do Avaliador - Processo " + idProcesso),
            corpo,
            true); // so exibir apos dataEnvio registrada
    }

    /**
     * Pedido de informacao complementar ao solicitante: quando um medico
     * avaliador pede mais informacoes, repassa-se o pedido a EQUIPE SOLICITANTE
     * (a que abriu o processo) para que complemente o processo. Texto pronto
     * para copiar/colar. Como o destinatario e o SOLICITANTE (nao os medicos
     * avaliadores), o e-mail PODE e DEVE conter o NOME COMPLETO do paciente.
     */
    private EmailTemplate emailSolicitaInfo(Processo p) {
        String idProcesso = p.getNumero() + " - Paciente " + p.getPacienteNome();

        String corpo = """
            Prezados(as),

            Informamos que, durante a analise do processo de Urgencia Renal %s,
            referente ao paciente %s, um(a) dos(as) avaliadores(as) da Urgencia
            Renal solicitou informacoes complementares para concluir o parecer.

            Equipe solicitante: %s

            Solicitamos, por gentileza, o envio das informacoes e/ou documentos
            adicionais necessarios a continuidade da analise, respondendo a este
            e-mail. Assim que recebidas, a analise sera retomada e o processo
            seguira para a decisao.

            Atenciosamente,
            %s
            """.formatted(p.getNumero(), p.getPacienteNome(), p.getSolicitanteEquipe(), assinatura());

        return new EmailTemplate("solicita-info",
            "Pedido de informacao complementar ao solicitante", "question-circle",
            assunto("Processo " + idProcesso + " - Solicitacao de informacoes complementares"),
            corpo);
    }

    private EmailTemplate emailDeferido(Processo p) {
        String corpo = """
            Prezados(as),

            Informamos que o processo de Urgencia Renal %s, referente ao paciente
            %s, foi DEFERIDO pela equipe de Urgencia Renal.

            Equipe solicitante: %s

            Segue EM ANEXO o comprovante de que a urgencia renal foi inserida no
            Sistema Nacional de Transplantes (SNT).

            Permanecemos a disposicao para esclarecimentos.

            Atenciosamente,
            %s
            """.formatted(p.getNumero(), p.getPacienteNome(), p.getSolicitanteEquipe(), assinatura());

        return new EmailTemplate("deferido", "Resposta ao solicitante (Deferido)", "check-circle",
            assunto("Processo " + p.getNumero() + " - DEFERIDO"), corpo);
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
            %s
            """.formatted(p.getNumero(), p.getPacienteNome(), p.getSolicitanteEquipe(), motivo, assinatura());

        return new EmailTemplate("indeferido", "Resposta ao solicitante (Indeferido)", "x-circle",
            assunto("Processo " + p.getNumero() + " - INDEFERIDO"), corpo);
    }
}
