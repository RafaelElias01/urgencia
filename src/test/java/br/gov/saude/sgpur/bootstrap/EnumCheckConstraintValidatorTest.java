package br.gov.saude.sgpur.bootstrap;

import br.gov.saude.sgpur.bootstrap.EnumCheckConstraintValidator.Divergencia;
import br.gov.saude.sgpur.domain.SituacaoUrgencia;
import br.gov.saude.sgpur.domain.StatusSolicitacaoOnline;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes da logica pura do {@link EnumCheckConstraintValidator} — a parte que
 * roda sem banco: o parsing do texto de {@code pg_get_constraintdef}, a
 * comparacao com o enum Java e o SQL de correcao gerado.
 *
 * <p>O parsing e o ponto mais fragil do mecanismo (o Postgres reescreve a
 * constraint em formas diferentes conforme o numero de valores e o tipo da
 * coluna), por isso concentra a maior parte dos casos aqui: forma
 * {@code = ANY (ARRAY[...])} com e sem os parenteses extras, valor unico
 * reescrito como {@code = 'X'::text}, {@code IN (...)} escrito a mao, valores
 * com underscore, e constraints que NAO sao lista de valores (que precisam ser
 * ignoradas para nao virar falso positivo).
 */
class EnumCheckConstraintValidatorTest {

    /** Texto real devolvido pelo Postgres de producao do SAUR (controle_urgencia). */
    private static final String DEF_CONTROLE_URGENCIA =
        "CHECK (((situacao)::text = ANY ((ARRAY['ATIVA'::character varying, "
            + "'RENOVADA'::character varying, 'EXPIRADA'::character varying, "
            + "'CANCELADA'::character varying])::text[])))";

    @Nested
    @DisplayName("parsearValoresDaConstraint")
    class Parser {

        @Test
        void extrai_valores_do_formato_real_do_postgres() {
            Optional<Set<String>> valores =
                EnumCheckConstraintValidator.parsearValoresDaConstraint(DEF_CONTROLE_URGENCIA);

            assertThat(valores).isPresent();
            assertThat(valores.get())
                .containsExactly("ATIVA", "RENOVADA", "EXPIRADA", "CANCELADA");
        }

        @Test
        void extrai_valores_com_underscore() {
            String def = "CHECK (((status)::text = ANY ((ARRAY['AGUARDANDO_TRIAGEM'::character varying, "
                + "'PROCESSO_EXCLUIDO'::character varying])::text[])))";

            assertThat(EnumCheckConstraintValidator.parsearValoresDaConstraint(def))
                .contains(Set.of("AGUARDANDO_TRIAGEM", "PROCESSO_EXCLUIDO"));
        }

        @Test
        void aceita_formato_sem_os_parenteses_extras() {
            String def = "CHECK ((status)::text = ANY (ARRAY['ENVIADO'::text, 'DEFERIDO'::text]))";

            assertThat(EnumCheckConstraintValidator.parsearValoresDaConstraint(def))
                .contains(Set.of("ENVIADO", "DEFERIDO"));
        }

        @Test
        @DisplayName("lista de 1 valor: o Postgres reescreve como = 'X'::text, sem ARRAY")
        void aceita_lista_de_um_valor_unico() {
            String def = "CHECK (((remetente)::text = 'SOLICITANTE'::text))";

            assertThat(EnumCheckConstraintValidator.parsearValoresDaConstraint(def))
                .contains(Set.of("SOLICITANTE"));
        }

        @Test
        void aceita_a_forma_IN_escrita_a_mao() {
            String def = "CHECK (situacao IN ('ATIVA', 'CANCELADA'))";

            assertThat(EnumCheckConstraintValidator.parsearValoresDaConstraint(def))
                .contains(Set.of("ATIVA", "CANCELADA"));
        }

        @Test
        void aceita_a_forma_IN_em_minusculo_e_sem_espaco() {
            String def = "CHECK (situacao in('ATIVA','CANCELADA'))";

            assertThat(EnumCheckConstraintValidator.parsearValoresDaConstraint(def))
                .contains(Set.of("ATIVA", "CANCELADA"));
        }

        @Test
        @DisplayName("ignora CHECK que nao e lista de valores (nao pode virar falso positivo)")
        void ignora_check_numerica() {
            assertThat(EnumCheckConstraintValidator.parsearValoresDaConstraint("CHECK ((versao >= 0))"))
                .isEmpty();
            assertThat(EnumCheckConstraintValidator.parsearValoresDaConstraint(
                "CHECK ((data_fim > data_inicio))")).isEmpty();
        }

        @Test
        void ignora_definicao_nula_ou_vazia() {
            assertThat(EnumCheckConstraintValidator.parsearValoresDaConstraint(null)).isEmpty();
            assertThat(EnumCheckConstraintValidator.parsearValoresDaConstraint("   ")).isEmpty();
        }

        @Test
        @DisplayName("nao confunde ANY sem literais (ex.: coluna comparada com subselect)")
        void ignora_any_sem_literais() {
            assertThat(EnumCheckConstraintValidator.parsearValoresDaConstraint(
                "CHECK ((codigo = ANY (outra_coluna)))")).isEmpty();
        }

        @Test
        void nao_duplica_valores_repetidos_na_definicao() {
            String def = "CHECK (status IN ('A', 'A', 'B'))";

            assertThat(EnumCheckConstraintValidator.parsearValoresDaConstraint(def))
                .contains(Set.of("A", "B"));
        }
    }

    @Nested
    @DisplayName("valoresFaltando")
    class Comparacao {

        @Test
        void detecta_valor_do_enum_ausente_na_constraint() {
            List<String> faltando = EnumCheckConstraintValidator.valoresFaltando(
                List.of("ATIVA", "RENOVADA", "EXPIRADA", "CANCELADA"),
                Set.of("ATIVA", "RENOVADA"));

            assertThat(faltando).containsExactly("EXPIRADA", "CANCELADA");
        }

        @Test
        void sem_divergencia_quando_a_constraint_cobre_todo_o_enum() {
            assertThat(EnumCheckConstraintValidator.valoresFaltando(
                List.of("A", "B"), Set.of("A", "B", "LEGADO"))).isEmpty();
        }

        @Test
        @DisplayName("valor extra na constraint (enum encolheu) nao e problema: nada quebra ao gravar")
        void valor_sobrando_na_constraint_nao_e_divergencia() {
            assertThat(EnumCheckConstraintValidator.valoresFaltando(
                List.of("A"), Set.of("A", "B", "C"))).isEmpty();
        }

        @Test
        void enum_sem_constantes_nao_gera_divergencia() {
            assertThat(EnumCheckConstraintValidator.valoresFaltando(List.of(), Set.of("A"))).isEmpty();
        }
    }

    @Nested
    @DisplayName("valoresDoEnum")
    class ValoresDoEnum {

        @Test
        void le_as_constantes_reais_do_enum_de_dominio() {
            assertThat(EnumCheckConstraintValidator.valoresDoEnum(SituacaoUrgencia.class))
                .containsExactly("ATIVA", "RENOVADA", "EXPIRADA", "CANCELADA");
        }

        @Test
        void classe_que_nao_e_enum_devolve_lista_vazia() {
            assertThat(EnumCheckConstraintValidator.valoresDoEnum(String.class)).isEmpty();
        }
    }

    @Nested
    @DisplayName("avaliar (parser + comparacao + SQL)")
    class Avaliacao {

        @Test
        @DisplayName("reproduz o bug real de 2026-07-27: PROCESSO_EXCLUIDO fora da constraint")
        void detecta_o_cenario_que_quebrou_producao() {
            // Constraint como estava no Postgres antes do valor PROCESSO_EXCLUIDO existir.
            String def = "CHECK (((status)::text = ANY ((ARRAY['ENVIADA'::character varying, "
                + "'CONVERTIDA'::character varying, 'DEVOLVIDA'::character varying, "
                + "'CANCELADA'::character varying, 'APROVADA'::character varying, "
                + "'REPROVADA'::character varying])::text[])))";

            Optional<Divergencia> d = EnumCheckConstraintValidator.avaliar(
                "solicitacao_online", "status", "StatusSolicitacaoOnline",
                "solicitacao_online_status_check", def,
                EnumCheckConstraintValidator.valoresDoEnum(StatusSolicitacaoOnline.class));

            assertThat(d).isPresent();
            assertThat(d.get().valoresFaltando()).containsExactly("PROCESSO_EXCLUIDO");
            assertThat(d.get().sqlCorrecao())
                .contains("ALTER TABLE solicitacao_online DROP CONSTRAINT solicitacao_online_status_check;")
                .contains("ADD CONSTRAINT solicitacao_online_status_check CHECK (status IN (")
                .contains("'PROCESSO_EXCLUIDO'");
            assertThat(d.get().descricaoCurta()).contains("solicitacao_online.status");
        }

        @Test
        void constraint_completa_nao_gera_divergencia() {
            Optional<Divergencia> d = EnumCheckConstraintValidator.avaliar(
                "controle_urgencia", "situacao", "SituacaoUrgencia",
                "controle_urgencia_situacao_check", DEF_CONTROLE_URGENCIA,
                EnumCheckConstraintValidator.valoresDoEnum(SituacaoUrgencia.class));

            assertThat(d).isEmpty();
        }

        @Test
        @DisplayName("CHECK que nao lista valores e ignorada, mesmo com o enum inteiro 'faltando'")
        void constraint_nao_relacionada_e_ignorada() {
            Optional<Divergencia> d = EnumCheckConstraintValidator.avaliar(
                "controle_urgencia", "situacao", "SituacaoUrgencia",
                "controle_urgencia_versao_check", "CHECK ((versao >= 0))",
                EnumCheckConstraintValidator.valoresDoEnum(SituacaoUrgencia.class));

            assertThat(d).isEmpty();
        }

        @Test
        void sql_de_correcao_lista_todos_os_valores_atuais_do_enum() {
            String sql = EnumCheckConstraintValidator.montarSqlCorrecao(
                "controle_urgencia", "situacao", "controle_urgencia_situacao_check",
                EnumCheckConstraintValidator.valoresDoEnum(SituacaoUrgencia.class));

            assertThat(sql).isEqualTo("""
                ALTER TABLE controle_urgencia DROP CONSTRAINT controle_urgencia_situacao_check;
                ALTER TABLE controle_urgencia ADD CONSTRAINT controle_urgencia_situacao_check \
                CHECK (situacao IN ('ATIVA', 'RENOVADA', 'EXPIRADA', 'CANCELADA'));""");
        }
    }
}
