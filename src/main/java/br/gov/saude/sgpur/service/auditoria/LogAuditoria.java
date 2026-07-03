package br.gov.saude.sgpur.service.auditoria;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca um metodo (tipicamente um handler de controller) para registro
 * automatico de auditoria apos retorno normal, via {@link AuditoriaAspect}.
 *
 * Use apenas em acoes SIMPLES (log fixo, sem logica condicional sobre o
 * resultado): o aspect grava sempre que o metodo retorna sem excecao. Acoes
 * cujo texto ou ocorrencia dependem do resultado devem continuar chamando
 * {@code AuditoriaService.registrar(...)} manualmente.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface LogAuditoria {

    /** Codigo da acao gravado no log (ex.: "PROCESSO_EXCLUIDO"). */
    String acao();

    /**
     * Detalhe do log. Aceita SpEL avaliado contra os argumentos do metodo,
     * expostos como {@code #args} (array). Ex.: {@code "'Processo id ' + #args[0]"}.
     * String sem prefixo de expressao e gravada literalmente.
     */
    String detalhe() default "";
}
