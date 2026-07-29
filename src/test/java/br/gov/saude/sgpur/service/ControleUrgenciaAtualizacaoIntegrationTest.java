package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.ControleUrgencia;
import br.gov.saude.sgpur.domain.SituacaoUrgencia;
import br.gov.saude.sgpur.repository.ControleUrgenciaRepository;
import br.gov.saude.sgpur.support.CamposDeFormulario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.beans.PropertyDescriptor;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Teste de INTEGRACAO (contexto Spring real + H2) de
 * {@link ControleUrgenciaService#atualizar(ControleUrgencia)}, contra a familia
 * de bugs de <b>escrita descartada em silencio</b>.
 *
 * <p><b>Por que com banco de verdade e nao com mock do repositorio:</b> o teste
 * unitario com {@code @Mock ControleUrgenciaRepository} devolvia a MESMA
 * instancia que o service acabara de mutar, entao "reler" era so olhar o objeto
 * em memoria - qualquer campo esquecido continuava com o valor antigo do
 * registro e o assert passava mesmo com o bug. Foi exatamente assim que a falta
 * do {@code dataVencimento} sobreviveu a suite inteira ate 2026-07-29. Aqui a
 * entidade e <b>relida do banco</b> depois do commit.
 *
 * <p>O caso {@link #cadaCampoDoFormularioDeEdicaoChegaAoBanco()} deriva a lista
 * de campos do proprio {@code controle-urgencias/form.html}: adicionar um campo
 * novo ao formulario e esquecer de copia-lo no service quebra este teste
 * automaticamente, sem precisar lembrar de vir aqui atualizar nada.
 */
@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:sgpur-controle-urgencia-update;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.anexos.dir=./target/test-anexos-controle-urgencia-update"
})
class ControleUrgenciaAtualizacaoIntegrationTest {

    private static final String FORM = "templates/controle-urgencias/form.html";

    /**
     * Campos que o formulario envia mas que o service IGNORA de proposito -
     * nao sao bug, sao defesa. Se um deles passar a ser copiado, este teste
     * (metodo {@link #camposDeSistemaContinuamIgnorados()}) quebra.
     */
    private static final List<String> IGNORADOS_DE_PROPOSITO = List.of("id");

    @Autowired
    private ControleUrgenciaService service;
    @Autowired
    private ControleUrgenciaRepository repo;

    private Long id;

    @BeforeEach
    void preparar() {
        repo.deleteAll();
        ControleUrgencia c = new ControleUrgencia("Maria Original", "RGCT-ORIG", "Equipe Origem", "O",
            SituacaoUrgencia.ATIVA, LocalDate.now().plusDays(30));
        c.setObservacoes("Observacao original");
        c = repo.saveAndFlush(c);
        // Estado que so as acoes proprias (renovar/cancelar) podem produzir -
        // serve para provar que a edicao nao o sobrescreve.
        c.setSituacao(SituacaoUrgencia.RENOVADA);
        c.setDataUltimaRenovacao(LocalDate.now().minusDays(2));
        c.setProcessoId(4242L);
        id = repo.saveAndFlush(c).getId();
    }

    /**
     * O padrao central contra a familia: altera TODOS os campos editaveis de
     * uma vez, com valores distintos e reconheciveis, salva, RELE do banco e
     * confere cada campo individualmente.
     */
    @Test
    void atualizaTodosOsCamposEditaveisEReleDoBanco() {
        ControleUrgencia dados = new ControleUrgencia("Joana Editada", "RGCT-NOVO", "Equipe Nova", "AB",
            null, LocalDate.now().plusDays(75));
        dados.setId(id);
        dados.setObservacoes("Observacao editada");

        service.atualizar(dados);

        ControleUrgencia doBanco = repo.findById(id).orElseThrow();
        assertThat(doBanco.getNomePaciente()).isEqualTo("Joana Editada");
        assertThat(doBanco.getRgct()).isEqualTo("RGCT-NOVO");
        assertThat(doBanco.getEquipe()).isEqualTo("Equipe Nova");
        assertThat(doBanco.getAbo()).isEqualTo("AB");
        assertThat(doBanco.getObservacoes()).isEqualTo("Observacao editada");
        // O campo que o service ignorava: prorrogacao fantasma na tela cuja
        // unica funcao e controlar o prazo de 30 dias.
        assertThat(doBanco.getDataVencimento()).isEqualTo(LocalDate.now().plusDays(75));
    }

    /**
     * Guarda automatica: cada {@code th:field} do formulario de edicao precisa
     * chegar ao banco (ou estar explicitamente na lista de ignorados de
     * proposito). Um campo novo no HTML sem a copia correspondente no service
     * derruba este teste sem intervencao humana.
     */
    @Test
    void cadaCampoDoFormularioDeEdicaoChegaAoBanco() throws Exception {
        List<String> campos = CamposDeFormulario.thFields(FORM);
        assertThat(campos).contains("nomePaciente", "dataVencimento");

        for (String campo : campos) {
            if (IGNORADOS_DE_PROPOSITO.contains(campo)) {
                continue;
            }
            ControleUrgencia atual = repo.findById(id).orElseThrow();
            PropertyDescriptor pd = new PropertyDescriptor(campo, ControleUrgencia.class);
            Object novoValor = CamposDeFormulario.valorDistinto(
                ControleUrgencia.class, campo, pd.getPropertyType(), pd.getReadMethod().invoke(atual));

            ControleUrgencia dados = copiaEditavel(atual);
            dados.setId(id);
            pd.getWriteMethod().invoke(dados, novoValor);

            service.atualizar(dados);

            Object gravado = pd.getReadMethod().invoke(repo.findById(id).orElseThrow());
            assertThat(gravado)
                .as("campo '%s' aparece no formulario de edicao mas nao foi gravado por "
                    + "ControleUrgenciaService.atualizar - escrita descartada em silencio", campo)
                .isEqualTo(novoValor);
        }
    }

    /**
     * Contraprova: situacao, carimbos de sistema e vinculo de origem NAO podem
     * ser copiados do formulario (o binding os entrega com o default do
     * construtor - ATIVA/true/null - e copiar isso ressuscitaria uma urgencia
     * cancelada a cada correcao de nome).
     */
    @Test
    void camposDeSistemaContinuamIgnorados() {
        ControleUrgencia dados = new ControleUrgencia("Joana Editada", "RGCT-NOVO", "Equipe Nova", "AB",
            SituacaoUrgencia.ATIVA, LocalDate.now().plusDays(75));
        dados.setId(id);
        dados.setDataUltimaRenovacao(LocalDate.now());
        dados.setProcessoId(999L);
        dados.setAtivo(true);

        service.atualizar(dados);

        ControleUrgencia doBanco = repo.findById(id).orElseThrow();
        assertThat(doBanco.getSituacao()).isEqualTo(SituacaoUrgencia.RENOVADA);
        assertThat(doBanco.getDataUltimaRenovacao()).isEqualTo(LocalDate.now().minusDays(2));
        assertThat(doBanco.getProcessoId()).isEqualTo(4242L);
        assertThat(doBanco.getDataCadastro()).isNotNull();
    }

    /**
     * Vencimento vazio nao pode virar "salvou e ignorou": a coluna e NOT NULL e
     * o formulario marca o campo como obrigatorio na edicao, entao chegar aqui
     * sem data e erro - e precisa ser barulhento.
     */
    @Test
    void vencimentoVazioFalhaEmVezDeSalvarPelaMetade() {
        ControleUrgencia dados = new ControleUrgencia("Joana Editada", "RGCT-NOVO", "Equipe Nova", "AB",
            null, null);
        dados.setId(id);

        assertThatThrownBy(() -> service.atualizar(dados))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("data de vencimento");

        ControleUrgencia doBanco = repo.findById(id).orElseThrow();
        assertThat(doBanco.getNomePaciente()).isEqualTo("Maria Original");
    }

    /** Cria um objeto "de formulario" com o estado editavel atual do registro. */
    private ControleUrgencia copiaEditavel(ControleUrgencia origem) {
        ControleUrgencia c = new ControleUrgencia(origem.getNomePaciente(), origem.getRgct(),
            origem.getEquipe(), origem.getAbo(), null, origem.getDataVencimento());
        c.setObservacoes(origem.getObservacoes());
        return c;
    }
}
