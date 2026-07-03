package br.gov.saude.sgpur.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.net.URI;

/**
 * No modo desktop, abre o navegador padrao na aplicacao logo apos o start,
 * dando a sensacao de um programa local. Falhas nunca interrompem a app.
 */
@Component
@Profile("desktop")
public class DesktopBrowserLauncher implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DesktopBrowserLauncher.class);

    @Value("${server.port:8080}")
    private int port;

    @Override
    public void run(ApplicationArguments args) {
        String url = "http://localhost:" + port;
        try {
            if (!GraphicsEnvironment.isHeadless()
                    && Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                log.info("SAUR aberto no navegador: {}", url);
            } else {
                log.info("SAUR rodando. Abra no navegador: {}", url);
            }
        } catch (Exception e) {
            log.warn("Nao foi possivel abrir o navegador automaticamente. Acesse {} - {}", url, e.getMessage());
        }
    }
}
