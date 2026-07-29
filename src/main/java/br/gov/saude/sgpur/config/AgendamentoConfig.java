package br.gov.saude.sgpur.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Liga o agendador do Spring ({@code @Scheduled}) SOMENTE quando a varredura
 * periodica de decisao automatica esta habilitada
 * ({@code app.decisao-automatica.varredura.habilitado=true} — ligada em
 * producao, desligada em dev/teste).
 *
 * <p>Fica separado de {@code SgpurApplication} de proposito: com
 * {@code @EnableScheduling} no proprio {@code @SpringBootApplication}, o pool
 * de agendamento subiria em TODO {@code @SpringBootTest} da suite, e qualquer
 * tarefa agendada futura passaria a rodar no meio dos testes, escrevendo no
 * banco em paralelo com as assercoes. Aqui, quando a propriedade esta
 * desligada, nem o agendador nem o
 * {@link br.gov.saude.sgpur.service.DecisaoAutomaticaScheduler} sao
 * registrados — nada roda sozinho.</p>
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(
    prefix = "app.decisao-automatica.varredura", name = "habilitado", havingValue = "true")
public class AgendamentoConfig {
}
