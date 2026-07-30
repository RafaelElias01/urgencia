package br.gov.saude.sgpur.bootstrap;

import br.gov.saude.sgpur.web.HomeController;
import br.gov.saude.sgpur.bootstrap.EnumCheckConstraintValidator.Divergencia;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.List;

/**
 * Leva ao Painel ({@code /}) o resultado da verificacao de CHECK constraints de
 * enum feita no boot por {@link EnumCheckConstraintValidator}.
 *
 * <p><b>Por que o Painel:</b> o log nao basta — o deploy do SAUR e automatico
 * (GitHub Actions) e ninguem le {@code journalctl} a cada push, entao um ERROR
 * no boot passaria despercebido ate a proxima quebra em producao. O Painel e a
 * primeira tela de qualquer sessao: o ADMIN <i>topa</i> com o aviso sem ir
 * procurar. O alerta e restrito a ADMIN no template (contem nome de tabela,
 * coluna e SQL de manutencao — nada que interesse a OPERADOR/AVALIADOR).
 *
 * <p>Escopo deliberadamente limitado ao {@link HomeController}
 * ({@code assignableTypes}): um {@code @ControllerAdvice} global obrigaria todo
 * {@code @WebMvcTest} do projeto a mockar mais um bean. Pelo mesmo motivo o
 * validador entra por {@link ObjectProvider} — em fatias de teste onde ele nao
 * existe, o atributo simplesmente vem vazio e o alerta nao e renderizado, em vez
 * de quebrar o contexto.
 */
@ControllerAdvice(assignableTypes = HomeController.class)
public class EnumCheckConstraintAdvice {

    private final ObjectProvider<EnumCheckConstraintValidator> validador;

    public EnumCheckConstraintAdvice(ObjectProvider<EnumCheckConstraintValidator> validador) {
        this.validador = validador;
    }

    /** Divergencias encontradas no boot (lista vazia = nada a mostrar). */
    @ModelAttribute("enumCheckDivergencias")
    public List<Divergencia> enumCheckDivergencias() {
        EnumCheckConstraintValidator bean = validador.getIfAvailable();
        return bean == null ? List.of() : bean.getResultado().divergencias();
    }
}
