package br.gov.saude.sgpur.e2e;

import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.domain.Perfil;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.e2e.pages.AvaliadorPage;
import br.gov.saude.sgpur.e2e.pages.PortalSolicitantePage;
import br.gov.saude.sgpur.e2e.pages.ProcessoDetalhePage;
import br.gov.saude.sgpur.e2e.pages.SolicitacoesOnlineTriagemPage;
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
 *   <li>Equipe Solicitante: login no Portal do Solicitante (/solicitante) e
 *       envio do pedido de urgencia renal (todo Processo nasce assim desde
 *       2026-07-27 - nao ha mais cadastro manual "do zero").</li>
 *   <li>Operador: login, revisa a solicitacao na fila de triagem
 *       (/processos/solicitacoes-online) e converte em processo - o
 *       Recebimento ja nasce concluido automaticamente - e registra o Envio
 *       aos 3 avaliadores.</li>
 *   <li>2 medicos avaliadores: cada um se autentica no Portal do Avaliador
 *       (/avaliador) e VOTA DE VERDADE no seu proprio processo - nao e o
 *       operador lancando o resultado por eles.</li>
 *   <li>Operador de volta: o sistema ja decidiu sozinho por maioria simples
 *       assim que o 2o voto formou maioria (gerando o oficio de
 *       indeferimento automaticamente) - o operador so confirma o envio da
 *       resposta final ao solicitante e abre o Relatorio Final em PDF
 *       gerado pelo sistema, confirmando que ele reflete a decisao.</li>
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

    /** Senha previsivel para o teste, ja compativel com a password policy (8+, maiuscula/minuscula/numero/especial). */
    private static final String SENHA_TESTE = "Senha123!";

    /** Cria um login AVALIADOR vinculado ao membro, com senha previsivel para o teste. */
    private void criarLoginAvaliador(String username, MembroUrgenciaRenal membro) {
        Usuario u = new Usuario();
        u.setUsername(username);
        u.setNome(membro.getNome());
        u.setPerfil(Perfil.AVALIADOR);
        usuarioService.criar(u, SENHA_TESTE, membro.getId());
    }

    /** Cria um login SOLICITANTE (equipe/e-mail vem do proprio cadastro, nao do formulario). */
    private void criarLoginSolicitante(String username, String equipe, String email) {
        Usuario u = new Usuario();
        u.setUsername(username);
        u.setNome(equipe);
        u.setEmail(email);
        u.setPerfil(Perfil.SOLICITANTE);
        u.setEquipeSolicitante(equipe);
        usuarioService.criar(u, SENHA_TESTE);
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
        criarLoginSolicitante("solicitante.e2e", "Equipe Teste E2E", "solicitante.e2e@example.com");

        try {
            // ===== Ator 0: Equipe Solicitante, pelo Portal do Solicitante =====
            // Desde 2026-07-27 nao ha mais cadastro manual de processo "do zero" -
            // todo Processo nasce de uma SolicitacaoOnline enviada por aqui e
            // depois convertida pelo operador (ver Ator 1 abaixo).
            Page janelaSolicitante = novoAtor();
            login(janelaSolicitante, "solicitante.e2e", SENHA_TESTE);
            new PortalSolicitantePage(janelaSolicitante)
                .abrirNova()
                .preencher("Paciente E2E da Silva", "123456789-00001",
                    LocalDate.now(), "Quadro clinico grave, urgencia renal necessaria (cenario E2E).")
                .enviar();
            janelaSolicitante.context().close();

            // ===== Ator 1: Operador =====
            login("admin", "Admin123!");
            assertThat(page.url()).doesNotContain("/login");

            ProcessoDetalhePage detalhe = new SolicitacoesOnlineTriagemPage(page)
                .abrir()
                .abrirPrimeiraPendente()
                .revisarEConverter()
                .preencher("01/2026", LocalDate.now(),
                    "Paciente E2E da Silva", "123456789-00001",
                    "Equipe Teste E2E", "solicitante.e2e@example.com")
                .selecionarMedicos(medicoIds)
                .cadastrar();

            Long processoId = extrairIdDaUrl(page.url());

            // Passo 1 (Recebimento) e SEMPRE automatico desde 2026-07-27: todo
            // processo ja nasce com essa etapa concluida, sem nenhuma acao manual
            // do operador (o endpoint de registrar recebimento foi removido).
            assertThat(detalhe.passoConcluido(1)).isTrue();

            detalhe
                .passo2_anexarDocumentoClinico(pdfPayload("laudo.pdf", "Laudo clinico anonimizado"))
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
            login(janelaMedico1, "avaliador.e2e.1", SENHA_TESTE);
            List<String> errosConsole = new java.util.ArrayList<>();
            janelaMedico1.onConsoleMessage(msg -> {
                if ("error".equals(msg.type())) errosConsole.add(msg.text());
            });
            // onConsoleMessage so pega chamadas explicitas a console.log/warn/error -
            // uma excecao JS nao tratada (ex.: elemento do modal ausente em
            // avaliador-votar.js) NAO passa por ali, e sim por onPageError. Sem este
            // listener, um erro real no fluxo do modal/voto passaria despercebido
            // pelas asserts de "sem erro no console" abaixo.
            List<String> errosPagina = new java.util.ArrayList<>();
            janelaMedico1.onPageError(errosPagina::add);
            AvaliadorPage portalMedico1 = new AvaliadorPage(janelaMedico1).abrirVotacao(processoId);
            // Confirma que o PDF anonimizado carrega embutido na propria tela
            // (sem precisar baixar) e que o CSP/X-Frame-Options - agora escopados
            // so pra essa rota (ver SecurityConfig.AVALIADOR_PDF_MATCHER) - nao
            // bloquearam o proprio app de se auto-enquadrar.
            assertThat(portalMedico1.materialInline().isVisible()).isTrue();
            screenshot(janelaMedico1, "avaliador-pdf-inline");
            assertThat(errosConsole).noneMatch(e ->
                e.contains("Refused to display") || e.contains("X-Frame-Options"));

            // O voto e definitivo (sem edicao posterior) - confirma que o modal de
            // ciencia bloqueia o envio ate o avaliador marcar o checkbox de leitura.
            portalMedico1.preencherEAbrirConfirmacao(
                "NAO_FAVORAVEL", "Achados clinicos nao sustentam a urgencia alegada.");
            assertThat(portalMedico1.botaoConfirmarModal().isDisabled()).isTrue();
            screenshot(janelaMedico1, "avaliador-modal-confirmacao-voto");
            assertThat(portalMedico1.checkboxConfirmaModal().isChecked()).isFalse();
            portalMedico1.confirmarNoModal();
            assertThat(errosPagina).isEmpty();

            Page janelaMedico2 = novoAtor();
            login(janelaMedico2, "avaliador.e2e.2", SENHA_TESTE);
            new AvaliadorPage(janelaMedico2)
                .abrirVotacao(processoId)
                .votar("NAO_FAVORAVEL", "Concordo com a avaliacao anterior: sem indicacao de urgencia.");
            janelaMedico2.context().close();

            // ===== De volta ao Operador: maioria simples ja formada (2 de 3 desfavoraveis) =====
            // O sistema DECIDE SOZINHO assim que o 2o parecer desfavoravel e
            // registrado (ProcessoService.tentarDecisaoAutomatica, chamado
            // pelo proprio AvaliadorController logo apos o voto) - inclusive
            // gerando o oficio de indeferimento automaticamente. Por isso nao
            // ha nenhum "passo4_decidir" manual aqui: ao recarregar a tela, os
            // passos 3 (Respostas) e 4 (Decisao) ja chegam concluidos.
            page.reload();
            page.waitForLoadState();
            assertThat(detalhe.passoConcluido(3)).isTrue();
            assertThat(detalhe.passoConcluido(4)).isTrue();

            // Passo 5: o oficio de indeferimento ja foi gerado automaticamente
            // pela decisao acima - falta so o operador confirmar o envio da
            // resposta final ao solicitante (unico botao, sem upload manual).
            detalhe.passo5_confirmarRespostaAoSolicitante();
            assertThat(detalhe.passoConcluido(5)).isTrue();

            // Percorre a tela inteira (rolagem suave) para dar tempo de ver o
            // processo concluido, com todos os anexos gerados, antes de abrir o PDF.
            mostrarPaginaInteira();

            // ===== Relatorio Final (PDF), gerado pelo sistema com o resultado =====
            // Abre visivelmente numa nova aba - clique real no botao, nao um fetch
            // em segundo plano, para quem esta acompanhando ver o PDF na tela.
            // Em modo headless o Chromium nao tem visualizador de PDF embutido
            // (trata a resposta como download, sem navegar/renderizar pagina) -
            // so validamos a URL e tiramos screenshot quando headed, de fato
            // visualizavel.
            Page abaRelatorio = detalhe.abrirRelatorioFinal(headed);
            if (headed) {
                assertThat(abaRelatorio.url()).contains("/relatorio");
                abaRelatorio.waitForTimeout(2000); // tempo pro visualizador de PDF renderizar antes do screenshot
                screenshot(abaRelatorio, "relatorio-final");
            }

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
