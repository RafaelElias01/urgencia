package br.gov.saude.sgpur;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.TimeZone;

/**
 * SAUR - Sistema de Avaliacao de Urgencia Renal.
 * Substitui a planilha Excel da equipe de Urgencia Renal da Secretaria de Saude.
 */
@SpringBootApplication
public class SgpurApplication {

    public static void main(String[] args) {
        // Garante que toda a JVM use o fuso horario de Brasilia (UTC-3).
        // Isso afeta LocalDateTime.now(), datas no banco H2/Postgres e logs.
        // Em producao tambem exporte TZ=America/Sao_Paulo no ambiente do servidor.
        TimeZone.setDefault(TimeZone.getTimeZone("America/Sao_Paulo"));
        SpringApplication.run(SgpurApplication.class, args);
    }
}
