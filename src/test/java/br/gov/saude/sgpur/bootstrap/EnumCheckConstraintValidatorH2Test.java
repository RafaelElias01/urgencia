package br.gov.saude.sgpur.bootstrap;

import br.gov.saude.sgpur.bootstrap.EnumCheckConstraintValidator.ColunaEnum;
import br.gov.saude.sgpur.bootstrap.EnumCheckConstraintValidator.Resultado;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comportamento do {@link EnumCheckConstraintValidator} dentro do contexto real
 * do Spring, contra o H2 usado em dev/testes.
 *
 * <p>Duas garantias: (1) em banco nao-PostgreSQL a verificacao e no-op — nao
 * consulta nada, nao altera nada e, acima de tudo, nao impede o boot (o proprio
 * fato de este {@code @SpringBootTest} subir ja prova isso); (2) a descoberta de
 * colunas de enum pelo metamodelo do Hibernate realmente encontra as colunas
 * mapeadas com {@code @Enumerated(EnumType.STRING)} do dominio, com os nomes
 * fisicos corretos — e o que garante que enums futuros sejam cobertos
 * automaticamente, sem lista fixa.
 *
 * <p>As mesmas propriedades do {@code SgpurApplicationTests} sao usadas de
 * proposito, para reaproveitar o contexto ja cacheado pelo Spring Test.
 */
@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:sgpur-test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
class EnumCheckConstraintValidatorH2Test {

    @Autowired
    private EnumCheckConstraintValidator validator;

    @Test
    @DisplayName("em H2 a verificacao nao roda e nao reporta divergencia")
    void no_op_fora_do_postgres() {
        Resultado resultado = validator.getResultado();

        assertThat(resultado.executado()).isFalse();
        assertThat(resultado.temDivergencia()).isFalse();
        assertThat(resultado.divergencias()).isEmpty();
        assertThat(resultado.observacao()).containsIgnoringCase("PostgreSQL");
    }

    @Test
    @DisplayName("rodar de novo em H2 continua sendo no-op silencioso (idempotente)")
    void reexecucao_continua_no_op() {
        validator.run(null);

        assertThat(validator.getResultado().executado()).isFalse();
        assertThat(validator.getResultado().divergencias()).isEmpty();
    }

    @Test
    @DisplayName("descobre as colunas @Enumerated(STRING) pelo metamodelo, com nome fisico snake_case")
    void descobre_colunas_de_enum_do_dominio() {
        Map<String, ColunaEnum> colunas = validator.mapearColunasDeEnum();

        // Amostra representativa: @Column(name=...) explicito, nome derivado pela
        // naming strategy (snake_case) e enum em tabela sem @Table customizado.
        assertThat(colunas).containsKeys(
            "processo.status",              // Processo.status -> StatusProcesso
            "anexo.tipo",                   // Anexo.tipo -> TipoAnexo
            "parecer.resultado",            // Parecer.resultado -> ResultadoParecer
            "parecer.origem",               // Parecer.origem -> OrigemParecer
            "usuario.perfil",               // Usuario.perfil -> Perfil
            "controle_urgencia.situacao",   // ControleUrgencia.situacao -> SituacaoUrgencia
            "solicitacao_online.status",    // SolicitacaoOnline.status -> StatusSolicitacaoOnline
            "mensagem_solicitacao.remetente");

        ColunaEnum situacao = colunas.get("controle_urgencia.situacao");
        assertThat(situacao.tabela()).isEqualTo("controle_urgencia");
        assertThat(situacao.coluna()).isEqualTo("situacao");
        assertThat(situacao.enumClasse()).isEqualTo("SituacaoUrgencia");
        assertThat(situacao.valores()).containsExactly("ATIVA", "RENOVADA", "EXPIRADA", "CANCELADA");

        // Nenhuma coluna que nao seja enum pode entrar na varredura.
        assertThat(colunas).doesNotContainKeys("processo.paciente_nome", "processo.id");
    }
}
