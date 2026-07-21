package br.gov.saude.sgpur.service.auditoria;

import br.gov.saude.sgpur.service.AuditoriaService;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Registra auditoria automaticamente para metodos anotados com
 * {@link LogAuditoria}, apos retorno normal. Reune o IP do cliente da
 * requisicao HTTP corrente (mesma origem que {@code request.getRemoteAddr()}
 * usado no Portal do Avaliador) e delega a {@link AuditoriaService}, que ja
 * absorve qualquer falha de log sem interromper a acao principal.
 */
@Aspect
@Component
public class AuditoriaAspect {

    private final AuditoriaService auditoria;
    private final ExpressionParser parser = new SpelExpressionParser();

    public AuditoriaAspect(AuditoriaService auditoria) {
        this.auditoria = auditoria;
    }

    @AfterReturning("@annotation(logAuditoria)")
    public void registrar(JoinPoint joinPoint, LogAuditoria logAuditoria) {
        auditoria.registrar(logAuditoria.acao(), detalhe(logAuditoria, joinPoint), ipDaRequisicao());
    }

    private String detalhe(LogAuditoria logAuditoria, JoinPoint joinPoint) {
        String expr = logAuditoria.detalhe();
        if (expr == null || expr.isBlank()) {
            return null;
        }
        try {
            EvaluationContext context = new StandardEvaluationContext();
            context.setVariable("args", joinPoint.getArgs());
            Expression expression = parser.parseExpression(expr);
            Object valor = expression.getValue(context);
            return valor != null ? valor.toString() : null;
        } catch (RuntimeException e) {
            // SpEL invalido nunca deve quebrar a acao — grava a expressao crua.
            return expr;
        }
    }

    private String ipDaRequisicao() {
        try {
            var attrs = RequestContextHolder.currentRequestAttributes();
            if (attrs instanceof ServletRequestAttributes servletAttrs) {
                return servletAttrs.getRequest().getRemoteAddr();
            }
        } catch (IllegalStateException e) {
            // fora de uma requisicao HTTP (ex.: chamada de fundo) — sem IP.
        }
        return null;
    }
}
