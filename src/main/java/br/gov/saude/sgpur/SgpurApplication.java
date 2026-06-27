package br.gov.saude.sgpur;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Sistema de Gestao de Processos de Urgencia Renal (SGPUR).
 * Substitui a planilha Excel da Camara Tecnica Estadual de Urgencia Renal.
 */
@SpringBootApplication
public class SgpurApplication {

    public static void main(String[] args) {
        SpringApplication.run(SgpurApplication.class, args);
    }
}
