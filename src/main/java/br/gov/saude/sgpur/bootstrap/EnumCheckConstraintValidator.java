package br.gov.saude.sgpur.bootstrap;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.metamodel.MappingMetamodel;
import org.hibernate.metamodel.mapping.SelectableMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Verificador de sanidade das CHECK constraints de colunas de enum no PostgreSQL.
 *
 * <h2>Qual bug isso previne</h2>
 * Colunas mapeadas com {@code @Enumerated(EnumType.STRING)} ganham do Hibernate,
 * no momento em que a tabela e criada, uma constraint
 * {@code CHECK (coluna IN (...))} com a lista de valores <b>congelada</b>. Como o
 * projeto usa {@code ddl-auto: update} e nao tem Flyway/Liquibase, adicionar um
 * valor novo ao enum Java <b>nunca</b> atualiza essa constraint — o {@code update}
 * so faz {@code ADD COLUMN}, jamais {@code DROP/ADD CONSTRAINT}. O resultado e um
 * {@code DataIntegrityViolationException} que so aparece <b>em producao</b>, porque
 * em dev/H2 o schema e recriado do zero com a lista atual. Ja aconteceu de verdade:
 * {@code StatusSolicitacaoOnline.PROCESSO_EXCLUIDO} (2026-07-27) quebrou toda
 * exclusao de processo vindo do Portal do Solicitante.
 *
 * <h2>Como descobre as colunas de enum</h2>
 * Por introspecao do <b>metamodelo de mapeamento do Hibernate</b>
 * ({@link MappingMetamodel}), nunca por lista fixa de tabelas/enums — o valor deste
 * mecanismo esta justamente em cobrir automaticamente qualquer enum futuro, sem
 * depender de alguem lembrar de cadastra-lo aqui. Percorre todos os
 * {@code SelectableMapping} (colunas fisicas) de todas as entidades e fica com os
 * que tem tipo Java enum gravado como texto no JDBC.
 *
 * <p>Optou-se pelo metamodelo do Hibernate em vez de varrer o pacote
 * {@code domain} com reflexao pura porque ele <b>ja resolveu os nomes fisicos</b>:
 * {@code @Table(name=...)}, {@code @Column(name=...)}, a naming strategy em vigor
 * (snake_case) e ate colunas de embeddables/heranca saem prontas, exatamente como
 * o Hibernate as escreve no banco. Reimplementar essa resolucao na mao seria a
 * parte mais facil de errar e sairia do ar silenciosamente se a estrategia de
 * nomenclatura mudasse.
 *
 * <h2>Garantias de comportamento</h2>
 * <ul>
 *   <li><b>Nunca derruba o boot.</b> Este sistema tem deploy automatico; uma falha
 *       de subida por causa de um diagnostico (possivelmente falso positivo)
 *       deixaria a Secretaria sem sistema. Tudo roda dentro de try/catch e no pior
 *       caso vira um WARN curto (sem stacktrace gigante).</li>
 *   <li><b>Nunca altera o banco.</b> So diagnostica e imprime o SQL de correcao —
 *       rodar {@code ALTER TABLE} em producao e decisao humana.</li>
 *   <li><b>So roda no PostgreSQL</b>, detectado pelo produto do banco na metadata
 *       da conexao (mais robusto que olhar o profile ativo). Em H2/dev/testes sai
 *       silenciosamente: la o schema e sempre recriado e a checagem nao faria
 *       sentido.</li>
 *   <li><b>Coluna de enum sem constraint nenhuma nao e problema</b> (e o caso da
 *       maioria hoje, depois que {@link SchemaMigration} passou a remove-las). O
 *       risco e constraint <i>existente e desatualizada</i>.</li>
 * </ul>
 *
 * <p>Como ninguem le {@code journalctl} a cada deploy, o resultado tambem fica
 * disponivel em memoria via {@link #getResultado()} e e exibido como alerta no
 * Painel para o perfil ADMIN (ver {@code EnumCheckConstraintAdvice}).
 */
@Component
@Order(2) // depois do SchemaMigration (@Order(1)), que pode remover constraints obsoletas
public class EnumCheckConstraintValidator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EnumCheckConstraintValidator.class);

    /** Literais de string dentro da definicao da constraint: 'ATIVA', 'PROCESSO_EXCLUIDO'... */
    private static final Pattern LITERAL_SQL = Pattern.compile("'([^']*)'");

    /**
     * Formas de constraint que representam "coluna pertence a esta lista".
     * Cobre o texto que o Postgres devolve em {@code pg_get_constraintdef}
     * ({@code = ANY (ARRAY[...])} ou {@code = 'X'::text} quando ha um unico valor)
     * e tambem a forma {@code IN (...)} de constraints escritas a mao.
     */
    private static final Pattern FORMA_DE_LISTA = Pattern.compile(
        "=\\s*ANY|\\bIN\\s*\\(|=\\s*'", Pattern.CASE_INSENSITIVE);

    private final EntityManagerFactory entityManagerFactory;
    private final JdbcTemplate jdbc;

    private volatile Resultado resultado = Resultado.naoExecutado("Verificacao ainda nao executada.");

    public EnumCheckConstraintValidator(EntityManagerFactory entityManagerFactory, JdbcTemplate jdbc) {
        this.entityManagerFactory = entityManagerFactory;
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------
    // Resultado consultavel (estado do bean)
    // ------------------------------------------------------------------

    /** Uma coluna de enum cuja CHECK constraint no banco nao conhece todos os valores do enum Java. */
    public record Divergencia(String tabela,
                              String coluna,
                              String enumClasse,
                              String constraint,
                              List<String> valoresFaltando,
                              String sqlCorrecao) {

        public String descricaoCurta() {
            return tabela + "." + coluna + " (" + enumClasse + "): faltam "
                + String.join(", ", valoresFaltando);
        }
    }

    /**
     * Resultado da verificacao feita no boot.
     *
     * @param executado    {@code true} se a checagem realmente rodou contra um PostgreSQL
     * @param observacao   por que nao rodou / resumo do que rodou (texto para log e tela)
     * @param divergencias constraints desatualizadas encontradas (vazio = tudo certo)
     */
    public record Resultado(boolean executado, String observacao, List<Divergencia> divergencias) {

        public Resultado {
            divergencias = divergencias == null ? List.of() : List.copyOf(divergencias);
        }

        static Resultado naoExecutado(String motivo) {
            return new Resultado(false, motivo, List.of());
        }

        public boolean temDivergencia() {
            return !divergencias.isEmpty();
        }
    }

    /** Estado da ultima verificacao (roda uma unica vez, no boot). */
    public Resultado getResultado() {
        return resultado;
    }

    // ------------------------------------------------------------------
    // Execucao no boot
    // ------------------------------------------------------------------

    @Override
    public void run(ApplicationArguments args) {
        try {
            verificar();
        } catch (Exception e) {
            // Falha do proprio verificador (SQL, permissao, banco indisponivel, API do
            // Hibernate mudada) jamais pode derrubar o boot nem poluir o log.
            resultado = Resultado.naoExecutado("Verificacao falhou: " + e.getMessage());
            log.warn("EnumCheckConstraintValidator: verificacao ignorada ({}: {}).",
                e.getClass().getSimpleName(), e.getMessage());
        }
    }

    private void verificar() {
        if (!isPostgres()) {
            resultado = Resultado.naoExecutado(
                "Banco nao e PostgreSQL - verificacao de CHECK de enum nao se aplica.");
            log.debug("EnumCheckConstraintValidator: banco nao-PostgreSQL, nada a verificar.");
            return;
        }

        Map<String, ColunaEnum> colunasEnum = mapearColunasDeEnum();
        if (colunasEnum.isEmpty()) {
            resultado = Resultado.naoExecutado("Nenhuma coluna @Enumerated(STRING) encontrada no metamodelo.");
            return;
        }

        List<Divergencia> divergencias = new ArrayList<>();
        for (Map<String, Object> linha : consultarCheckConstraints()) {
            String tabela = texto(linha.get("tabela"));
            String coluna = texto(linha.get("coluna"));
            ColunaEnum alvo = colunasEnum.get(chave(tabela, coluna));
            if (alvo == null) {
                continue; // CHECK de coluna que nao e enum: nao e assunto deste verificador
            }
            avaliar(tabela, coluna, alvo.enumClasse(), texto(linha.get("nome_constraint")),
                texto(linha.get("definicao")), alvo.valores())
                .ifPresent(divergencias::add);
        }

        divergencias.sort(Comparator.comparing(Divergencia::tabela).thenComparing(Divergencia::coluna));
        resultado = new Resultado(true, resumo(colunasEnum.size(), divergencias.size()), divergencias);
        registrarNoLog(divergencias);
    }

    private static String resumo(int colunasVerificadas, int comDivergencia) {
        if (comDivergencia == 0) {
            return colunasVerificadas + " coluna(s) de enum verificada(s): nenhuma CHECK constraint desatualizada.";
        }
        return colunasVerificadas + " coluna(s) de enum verificada(s): " + comDivergencia
            + " com CHECK constraint desatualizada.";
    }

    private void registrarNoLog(List<Divergencia> divergencias) {
        if (divergencias.isEmpty()) {
            log.info("EnumCheckConstraintValidator: {}", resultado.observacao());
            return;
        }
        for (Divergencia d : divergencias) {
            log.error("""
                [CHECK CONSTRAINT DESATUALIZADA] Tabela {} coluna {} (enum {}).
                A constraint "{}" nao aceita o(s) valor(es): {}.
                Gravar qualquer um deles vai falhar EM PRODUCAO com DataIntegrityViolationException.
                Corrija executando no banco:
                {}""",
                d.tabela(), d.coluna(), d.enumClasse(), d.constraint(),
                String.join(", ", d.valoresFaltando()), d.sqlCorrecao());
        }
    }

    // ------------------------------------------------------------------
    // Acesso ao banco
    // ------------------------------------------------------------------

    /** Detecta o banco pela metadata da conexao (nao pelo profile ativo). */
    private boolean isPostgres() {
        Boolean postgres = jdbc.execute((Connection con) -> {
            DatabaseMetaData meta = con.getMetaData();
            String produto = meta == null ? "" : String.valueOf(meta.getDatabaseProductName());
            return produto.toLowerCase(Locale.ROOT).contains("postgre");
        });
        return Boolean.TRUE.equals(postgres);
    }

    /** Todas as CHECK constraints do schema corrente, uma linha por coluna envolvida. */
    private List<Map<String, Object>> consultarCheckConstraints() {
        return jdbc.queryForList("""
            SELECT rel.relname               AS tabela,
                   att.attname               AS coluna,
                   con.conname               AS nome_constraint,
                   pg_get_constraintdef(con.oid) AS definicao
              FROM pg_constraint con
              JOIN pg_class rel      ON rel.oid = con.conrelid
              JOIN pg_namespace ns   ON ns.oid = rel.relnamespace
              JOIN unnest(con.conkey) AS k(attnum) ON TRUE
              JOIN pg_attribute att   ON att.attrelid = rel.oid AND att.attnum = k.attnum
             WHERE con.contype = 'c'
               AND ns.nspname = current_schema()
            """);
    }

    // ------------------------------------------------------------------
    // Descoberta das colunas de enum (metamodelo do Hibernate)
    // ------------------------------------------------------------------

    /** Uma coluna fisica mapeada a partir de um enum gravado como texto. */
    record ColunaEnum(String tabela, String coluna, String enumClasse, List<String> valores) { }

    /**
     * Varre o metamodelo do Hibernate e devolve, por {@code tabela.coluna} (minusculo),
     * a classe do enum e seus valores atuais.
     */
    Map<String, ColunaEnum> mapearColunasDeEnum() {
        Map<String, ColunaEnum> mapa = new LinkedHashMap<>();
        SessionFactoryImplementor sf = entityManagerFactory.unwrap(SessionFactoryImplementor.class);
        sf.getMappingMetamodel().forEachEntityDescriptor(persister ->
            persister.forEachSelectable((indice, selectable) -> coletar(selectable, mapa)));
        return mapa;
    }

    private void coletar(SelectableMapping selectable, Map<String, ColunaEnum> mapa) {
        try {
            if (selectable == null || selectable.isFormula()) {
                return;
            }
            var jdbcMapping = selectable.getJdbcMapping();
            if (jdbcMapping == null || jdbcMapping.getJdbcType() == null) {
                return;
            }
            Class<?> tipoJava = jdbcMapping.getJavaTypeDescriptor() == null
                ? null : jdbcMapping.getJavaTypeDescriptor().getJavaTypeClass();
            // Enum gravado como texto = o unico caso que gera CHECK com lista de literais.
            if (tipoJava == null || !tipoJava.isEnum() || !jdbcMapping.getJdbcType().isStringLike()) {
                return;
            }
            String tabela = nomeSimples(selectable.getContainingTableExpression());
            String coluna = nomeSimples(selectable.getSelectionExpression());
            if (tabela.isEmpty() || coluna.isEmpty()) {
                return;
            }
            List<String> valores = valoresDoEnum(tipoJava);
            mapa.putIfAbsent(chave(tabela, coluna),
                new ColunaEnum(tabela, coluna, tipoJava.getSimpleName(), valores));
        } catch (Exception e) {
            // Um mapeamento exotico nao pode invalidar a varredura inteira.
            log.debug("EnumCheckConstraintValidator: coluna ignorada na varredura: {}", e.getMessage());
        }
    }

    /** Remove aspas e prefixo de schema ({@code "public"."processo"} -> {@code processo}). */
    private static String nomeSimples(String expressao) {
        if (expressao == null) {
            return "";
        }
        String limpo = expressao.replace("\"", "").replace("`", "").trim();
        int ponto = limpo.lastIndexOf('.');
        return ponto >= 0 ? limpo.substring(ponto + 1) : limpo;
    }

    private static String chave(String tabela, String coluna) {
        return tabela.toLowerCase(Locale.ROOT) + "." + coluna.toLowerCase(Locale.ROOT);
    }

    private static String texto(Object valor) {
        return valor == null ? "" : String.valueOf(valor);
    }

    // ------------------------------------------------------------------
    // Logica pura (sem banco) - coberta por testes unitarios
    // ------------------------------------------------------------------

    /** Nomes das constantes de um enum, na ordem de declaracao. */
    public static List<String> valoresDoEnum(Class<?> enumClasse) {
        Object[] constantes = enumClasse.getEnumConstants();
        if (constantes == null) {
            return List.of();
        }
        List<String> nomes = new ArrayList<>(constantes.length);
        for (Object c : constantes) {
            nomes.add(((Enum<?>) c).name());
        }
        return nomes;
    }

    /**
     * Extrai os valores aceitos por uma CHECK constraint a partir do texto devolvido
     * por {@code pg_get_constraintdef}.
     *
     * <p>Formas reconhecidas (todas ja vistas em bancos reais):
     * <pre>
     * CHECK (((situacao)::text = ANY ((ARRAY['ATIVA'::character varying, 'RENOVADA'::character varying])::text[])))
     * CHECK ((status)::text = ANY (ARRAY['A'::text, 'B'::text]))
     * CHECK (((remetente)::text = 'SOLICITANTE'::text))   -- lista de 1 valor
     * CHECK (status IN ('A', 'B'))                        -- constraint escrita a mao
     * </pre>
     *
     * <p>Devolve {@link Optional#empty()} quando a constraint <b>nao</b> e uma lista de
     * valores (ex.: {@code CHECK ((valor >= 0))}, {@code CHECK (fim > inicio)}) — nesses
     * casos ela nao tem nada a ver com o enum e deve ser ignorada, nunca reportada.
     */
    public static Optional<Set<String>> parsearValoresDaConstraint(String definicao) {
        if (definicao == null || definicao.isBlank()) {
            return Optional.empty();
        }
        if (!FORMA_DE_LISTA.matcher(definicao).find()) {
            return Optional.empty();
        }
        Set<String> valores = new LinkedHashSet<>();
        Matcher m = LITERAL_SQL.matcher(definicao);
        while (m.find()) {
            String valor = m.group(1).trim();
            if (!valor.isEmpty()) {
                valores.add(valor);
            }
        }
        return valores.isEmpty() ? Optional.empty() : Optional.of(valores);
    }

    /** Valores do enum Java que a constraint do banco nao aceita (ordem de declaracao). */
    public static List<String> valoresFaltando(List<String> valoresDoEnum, Set<String> valoresAceitos) {
        if (valoresDoEnum == null || valoresDoEnum.isEmpty()) {
            return List.of();
        }
        Set<String> aceitos = valoresAceitos == null ? Set.of() : valoresAceitos;
        return valoresDoEnum.stream().filter(v -> !aceitos.contains(v)).toList();
    }

    /** SQL pronto (DROP + ADD) para recriar a constraint com a lista atual do enum. */
    public static String montarSqlCorrecao(String tabela, String coluna, String constraint,
                                           List<String> valoresDoEnum) {
        String lista = valoresDoEnum.stream()
            .map(v -> "'" + v + "'")
            .collect(Collectors.joining(", "));
        return "ALTER TABLE " + tabela + " DROP CONSTRAINT " + constraint + ";\n"
            + "ALTER TABLE " + tabela + " ADD CONSTRAINT " + constraint
            + " CHECK (" + coluna + " IN (" + lista + "));";
    }

    /**
     * Compara uma CHECK constraint com o enum Java correspondente.
     *
     * @return a divergencia encontrada, ou vazio quando esta tudo certo / quando a
     *         constraint nao e uma lista de valores (logo, nao diz respeito ao enum)
     */
    public static Optional<Divergencia> avaliar(String tabela, String coluna, String enumClasse,
                                                String constraint, String definicao,
                                                List<String> valoresDoEnum) {
        Optional<Set<String>> aceitos = parsearValoresDaConstraint(definicao);
        if (aceitos.isEmpty()) {
            return Optional.empty();
        }
        List<String> faltando = valoresFaltando(valoresDoEnum, aceitos.get());
        if (faltando.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Divergencia(tabela, coluna, enumClasse, constraint, faltando,
            montarSqlCorrecao(tabela, coluna, constraint, valoresDoEnum)));
    }
}
