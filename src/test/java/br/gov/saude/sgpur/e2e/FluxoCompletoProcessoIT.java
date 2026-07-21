package br.gov.saude.sgpur.e2e;

import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.domain.Perfil;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.e2e.pages.AvaliadorPage;
import br.gov.saude.sgpur.e2e.pages.NovoProcessoPage;
import br.gov.saude.sgpur.e2e.pages.ProcessoDetalhePage;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.service.UsuarioService;
import com.lowagie.text.Document;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.FilePayload;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Simula, atraves de um browser Chromium real (Playwright), TODOS os atores
 * humanos do fluxo do processo de Urgencia Renal, cada um na sua propria
 * sessao (janela/BrowserContext independente, como se fossem computadores
 * diferentes):
 *
 * <ol>
 *   <li>Operador: login, cadastro do processo, Recebimento, Envio aos 3
 *       avaliadores.</li>
 *   <li>2 medicos avaliadores: cada um se autentica no Portal do Avaliador
 *       (/avaliador) e VOTA DE VERDADE no seu proprio processo - nao e o
 *       operador lancando o resultado por eles.</li>
 *   <li>Operador de volta: decide (maioria simples), finaliza (comprovantes
 *       + resposta ao solicitante) e abre o Relatorio Final em PDF gerado
 *       pelo sistema, confirmando que ele reflete a decisao.</li>
 * </ol>
 *
 * <p>E um "bot de navegacao": nenhuma chamada de servico ou endpoint e feita
 * diretamente - toda acao e um clique/preenchimento/upload/voto real na
 * tela, exatamente como aconteceria na vida real. Objetivo: pegar
 * regressoes que testes de unidade/MockMvc nao veem (JavaScript quebrado,
 * campo com o "name" errado, wizard travando numa aba, portal do avaliador
 * fora de sincronia com o operador etc).
 *
 * <p>Roda via ".\e2e.ps1" (browser visivel por padrao, com legendas
 * narrando cada acao) ou "mvn verify -Pe2e".
 */
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:sgpur-e2e;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
class FluxoCompletoProcessoIT extends PlaywrightTestBase {

    @Autowired
    private MembroUrgenciaRenalRepository membroRepository;
    @Autowired
    private UsuarioService usuarioService;

    private static byte[] pdf(String texto) {
        Document doc = new Document();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfWriter.getInstance(doc, out);
        doc.open();
        doc.add(new Paragraph(texto));
        doc.close();
        return out.toByteArray();
    }

    private static FilePayload pdfPayload(String nome, String texto) {
        return new FilePayload(nome, "application/pdf", pdf(texto));
    }

    /** Cria um login AVALIADOR vinculado ao membro, com senha previsivel para o teste. */
    private void criarLoginAvaliador(String username, MembroUrgenciaRenal membro) {
        Usuario u = new Usuario();
        u.setUsername(username);
        u.setNome(membro.getNome());
        u.setPerfil(Perfil.AVALIADOR);
        usuarioService.criar(u, "senha123", membro.getId());
    }

    @Test
    void fluxoCompletoComVotacaoRealDosAvaliadoresERelatorioFinal() {
        // O MembroDevSeed (perfil dev) ja populou 3 avaliadores no boot.
        List<MembroUrgenciaRenal> medicos = membroRepository.findByAtivoTrueOrderByInstituicaoAsc();
        assertThat(medicos).hasSize(3);
        List<Long> medicoIds = medicos.stream().map(MembroUrgenciaRenal::getId).toList();

        MembroUrgenciaRenal medico1 = medicos.get(0);
        MembroUrgenciaRenal medico2 = medicos.get(1);
        criarLoginAvaliador("avaliador.e2e.1", medico1);
        criarLoginAvaliador("avaliador.e2e.2", medico2);

        try {
            // ===== Ator 1: Operador =====
            login("admin", "admin123");
            assertThat(page.url()).doesNotContain("/login");

            ProcessoDetalhePage detalhe = new NovoProcessoPage(page)
                .abrir()
                .preencher("01/2026", LocalDate.now(),
                    "Paciente E2E da Silva", "123456789-00001",
                    "Equipe Teste E2E", "solicitante.e2e@example.com")
                .selecionarMedicos(medicoIds)
                .cadastrar();

            Long processoId = extrairIdDaUrl(page.url());

            detalhe.passo1_registrarRecebimento(pdfPayload("solicitacao.pdf", "Solicitacao original do paciente"));
            assertThat(detalhe.passoConcluido(1)).isTrue();

            detalhe
                .passo2_anexarDocumentoClinico(pdfPayload("laudo.pdf", "Laudo clinico anonimizado"))
                .passo2_anexarComprovanteEnvio(pdfPayload("comprovante-envio.pdf", "Comprovante de envio por e-mail"))
                .passo2_registrarEnvio();
            assertThat(detalhe.passoConcluido(2)).isTrue();

            // ===== Atores 2 e 3: os proprios medicos votando no Portal do Avaliador =====
            // Cada um numa janela/sessao propria - o operador continua logado na dele.
            // As janelas ficam abertas ate o fim do teste (fechadas em bloco no
            // @AfterEach) - fecha-las manualmente aqui, no meio do fluxo, chegou a
            // causar TargetClosedError em outra parte do teste (race condition:
            // close() e assincrono no driver do Playwright e pode disparar antes
            // de operacoes daquele context terminarem de fato).
            Page janelaMedico1 = novoAtor();
            login(janelaMedico1, "avaliador.e2e.1", "senha123");
            new AvaliadorPage(janelaMedico1)
                .abrirVotacao(processoId)
                .votar("NAO_FAVORAVEL", "Achados clinicos nao sustentam a urgencia alegada.");

            Page janelaMedico2 = novoAtor();
            login(janelaMedico2, "avaliador.e2e.2", "senha123");
            new AvaliadorPage(janelaMedico2)
                .abrirVotacao(processoId)
                .votar("NAO_FAVORAVEL", "Concordo com a avaliacao anterior: sem indicacao de urgencia.");
            janelaMedico2.context().close();

            // ===== De volta ao Operador: maioria simples ja formada (2 de 3 desfavoraveis) =====
            page.reload();
            page.waitForLoadState();
            assertThat(detalhe.passoConcluido(3)).isTrue();

            detalhe.passo4_decidir("INDEFERIDO", "Maioria dos avaliadores considerou o pedido desfavoravel.");
            assertThat(detalhe.passoConcluido(4)).isTrue();

            detalhe
                .passo5_anexarComprovanteEnvioSolicitante(pdfPayload("comprovante-resposta.pdf", "Comprovante de envio ao solicitante"))
                .passo5_confirmarRespostaAoSolicitante();
            assertThat(detalhe.passoConcluido(5)).isTrue();

            // Percorre a tela inteira (rolagem suave) para dar tempo de ver o
            // processo concluido, com todos os anexos gerados, antes de abrir o PDF.
            mostrarPaginaInteira();

            // ===== Relatorio Final (PDF), gerado pelo sistema com o resultado =====
            // Abre visivelmente numa nova aba - clique real no botao, nao um fetch
            // em segundo plano, para quem esta acompanhando ver o PDF na tela.
            Page abaRelatorio = detalhe.abrirRelatorioFinal();
            assertThat(abaRelatorio.url()).contains("/relatorio");
            abaRelatorio.waitForTimeout(2000); // tempo pro visualizador de PDF renderizar antes do screenshot
            screenshot(abaRelatorio, "relatorio-final");

        } catch (AssertionError | RuntimeException e) {
            screenshot("fluxo-completo-falha");
            throw e;
        }
    }

    /** Extrai o id numerico do processo da URL de detalhe (".../processos/{id}"). */
    private static Long extrairIdDaUrl(String url) {
        String semQuery = url.split("[?#]")[0];
        String[] partes = semQuery.split("/");
        return Long.parseLong(partes[partes.length - 1]);
    }
}
