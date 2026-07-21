package br.gov.saude.sgpur.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.io.File;

/**
 * Servico de envio de e-mails via SMTP (Gmail).
 * <p>
 * Configuracao das credenciais em application-local.yml (gitignored) ou
 * via variaveis de ambiente (SGPUR_MAIL_USER, SGPUR_MAIL_PASS).
 * <p>
 * Atualmente envia apenas e-mails em texto simples. Suporte a anexos
 * pode ser adicionado conforme necessidade.
 */
@Service
public class EmailSenderService {

    private static final Logger log = LoggerFactory.getLogger(EmailSenderService.class);

    private final JavaMailSender mailSender;
    private final String from;
    private final String overrideRecipient;

    public EmailSenderService(JavaMailSender mailSender,
                              @Value("${spring.mail.properties.mail.from:}") String from,
                              @Value("${app.mail.override-recipient:}") String overrideRecipient) {
        this.mailSender = mailSender;
        this.from = from;
        this.overrideRecipient = overrideRecipient;
        if (overrideRecipient != null && !overrideRecipient.isBlank()) {
            log.warn("EmailSender: MODO TESTE ATIVO - todo e-mail sera redirecionado para {} "
                    + "(app.mail.override-recipient). NAO usar isso em producao.", overrideRecipient);
        }
    }

    /**
     * Em dev/teste (app.mail.override-recipient configurado), redireciona
     * todo envio para um unico endereco fixo, preservando no assunto quem
     * seriam os destinatarios reais - evita mandar e-mail de teste para
     * avaliadores/solicitantes de verdade. Sem override, retorna os dados
     * originais inalterados (comportamento de producao).
     */
    private record DestinoResolvido(String[] to, String[] cc, String subject) {}

    private DestinoResolvido resolverDestino(String[] to, String[] cc, String subject) {
        if (overrideRecipient == null || overrideRecipient.isBlank()) {
            return new DestinoResolvido(to, cc, subject);
        }
        String originais = String.join(", ", to != null ? to : new String[0]);
        if (cc != null && cc.length > 0) {
            originais += " | cc: " + String.join(", ", cc);
        }
        return new DestinoResolvido(
            new String[]{overrideRecipient}, null,
            "[TESTE - para: " + originais + "] " + subject);
    }

    /**
     * Envia um e-mail em texto simples para um destinatario.
     *
     * @param to      endereco de e-mail do destinatario
     * @param subject assunto do e-mail
     * @param body    corpo em texto simples
     * @return true se enviou com sucesso, false caso contrario
     */
    public boolean enviar(String to, String subject, String body) {
        return enviar(new String[]{to}, null, subject, body);
    }

    /**
     * Envia e-mail para multiplos destinatarios com copia (CC) opcional.
     */
    public boolean enviar(String[] to, String[] cc, String subject, String body) {
        if (to == null || to.length == 0) {
            log.warn("EmailSender: nenhum destinatario informado, e-mail nao enviado.");
            return false;
        }
        if (from == null || from.isBlank()) {
            log.warn("EmailSender: remetente (from) nao configurado. "
                    + "Defina SGPUR_MAIL_USER ou spring.mail.properties.mail.from.");
            return false;
        }
        DestinoResolvido destino = resolverDestino(to, cc, subject);
        try {
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, false, "UTF-8");
            helper.setFrom(from);
            helper.setTo(destino.to());
            if (destino.cc() != null && destino.cc().length > 0) {
                helper.setCc(destino.cc());
            }
            helper.setSubject(destino.subject());
            helper.setText(body, false); // false = texto simples

            mailSender.send(msg);
            log.info("EmailSender: e-mail enviado para {} - assunto: {}",
                String.join(", ", destino.to()), destino.subject());
            return true;
        } catch (MailException | MessagingException e) {
            log.error("EmailSender: falha ao enviar e-mail para {}: {}",
                String.join(", ", destino.to()), e.getMessage());
            return false;
        }
    }

    /**
     * Envia e-mail com anexo.
     *
     * @param to        destinatario
     * @param subject   assunto
     * @param body      corpo do e-mail
     * @param anexo     arquivo a anexar
     * @param nomeAnexo nome de exibicao do anexo no e-mail
     * @return true se enviou com sucesso
     */
    public boolean enviarComAnexo(String to, String subject, String body,
                                  File anexo, String nomeAnexo) {
        if (from == null || from.isBlank()) {
            log.warn("EmailSender: remetente (from) nao configurado.");
            return false;
        }
        DestinoResolvido destino = resolverDestino(new String[]{to}, null, subject);
        try {
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(destino.to());
            helper.setSubject(destino.subject());
            helper.setText(body, false);
            if (anexo != null && anexo.exists()) {
                helper.addAttachment(nomeAnexo != null ? nomeAnexo : anexo.getName(), anexo);
            }
            mailSender.send(msg);
            log.info("EmailSender: e-mail com anexo enviado para {}", String.join(", ", destino.to()));
            return true;
        } catch (MailException | MessagingException e) {
            log.error("EmailSender: falha ao enviar e-mail com anexo para {}: {}",
                String.join(", ", destino.to()), e.getMessage());
            return false;
        }
    }
}
