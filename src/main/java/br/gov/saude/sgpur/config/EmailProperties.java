package br.gov.saude.sgpur.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Valores literais e estaveis usados na geracao dos textos de e-mail
 * (EmailTemplateService): assinatura padrao e prefixo dos assuntos.
 * Nao inclui os corpos de texto em si (parametrizados com dados dinamicos
 * do processo), apenas o que faz sentido trocar sem recompilar.
 */
@Component
@ConfigurationProperties(prefix = "sgpur.email")
public class EmailProperties {

    private String assinatura = "Equipe de Urgencia Renal - Secretaria de Saude";
    private String prefixoAssunto = "Urgencia Renal";

    public String getAssinatura() {
        return assinatura;
    }

    public void setAssinatura(String assinatura) {
        this.assinatura = assinatura;
    }

    public String getPrefixoAssunto() {
        return prefixoAssunto;
    }

    public void setPrefixoAssunto(String prefixoAssunto) {
        this.prefixoAssunto = prefixoAssunto;
    }
}
