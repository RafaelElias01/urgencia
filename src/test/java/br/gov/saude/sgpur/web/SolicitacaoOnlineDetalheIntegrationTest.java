package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.AnexoSolicitacaoOnline;
import br.gov.saude.sgpur.domain.Perfil;
import br.gov.saude.sgpur.domain.SolicitacaoOnline;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.repository.AnexoSolicitacaoOnlineRepository;
import br.gov.saude.sgpur.repository.SolicitacaoOnlineRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regressao do 500 nas telas de DETALHE da solicitacao online (reportado em
 * producao em 2026-07-26, tanto em /solicitante/{id} quanto na triagem do
 * operador).
 *
 * Causa: {@code spring.jpa.open-in-view: false} + templates que iteram
 * {@code solicitacao.anexos} (e leem {@code processoGerado}), ambos LAZY. O
 * Thymeleaf renderiza DEPOIS do commit da transacao do controller, entao o
 * proxy nao inicializado estourava {@code LazyInitializationException}.
 *
 * Estes testes sobem o contexto REAL (service + repositorio + Thymeleaf), sem
 * mock do service - e exatamente por isso pegam o bug, coisa que os
 * {@code @WebMvcTest} de {@code SolicitanteControllerTest}/
 * {@code SolicitacaoOnlineTriagemControllerTest} nao fazem, porque la o
 * service e mockado e nunca ha proxy LAZY nenhum.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:sgpur-solic-detalhe;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.solicitante.habilitado=true"
})
class SolicitacaoOnlineDetalheIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private UsuarioRepository usuarioRepo;
    @Autowired private SolicitacaoOnlineRepository solicitacaoRepo;
    @Autowired private AnexoSolicitacaoOnlineRepository anexoRepo;

    private Long solicitacaoId;

    @BeforeEach
    @Transactional
    void preparar() {
        anexoRepo.deleteAll();
        solicitacaoRepo.deleteAll();

        Usuario dono = usuarioRepo.findByUsername("solicitante-it").orElseGet(() -> {
            Usuario u = new Usuario();
            u.setUsername("solicitante-it");
            u.setSenha("{noop}x");
            u.setNome("Equipe Solicitante IT");
            u.setEmail("solicitante-it@example.com");
            u.setPerfil(Perfil.SOLICITANTE);
            u.setEquipeSolicitante("HCPA");
            return usuarioRepo.save(u);
        });

        SolicitacaoOnline s = new SolicitacaoOnline();
        s.setUsuarioSolicitante(dono);
        s.setPacienteNome("Maria Souza da Silva");
        s.setPacienteRgct("123456");
        s.setSolicitanteEquipe("HCPA");
        s.setSolicitanteEmail("solicitante-it@example.com");
        s.setDataSituacaoEspecial(LocalDate.now());
        s.setJustificativaClinica("Justificativa clinica de teste.");
        solicitacaoRepo.saveAndFlush(s);
        solicitacaoId = s.getId();

        AnexoSolicitacaoOnline a = new AnexoSolicitacaoOnline();
        a.setSolicitacaoOnline(s);
        a.setNomeArquivo("exame.pdf");
        a.setContentType("application/pdf");
        a.setTamanhoBytes(10L);
        a.setCaminhoArmazenado("solicitacoes/" + s.getId() + "/exame.pdf");
        anexoRepo.saveAndFlush(a);
    }

    @Test
    @WithMockUser(username = "solicitante-it", roles = "SOLICITANTE")
    void solicitanteVeOProprioDetalheComAnexosSemErro500() throws Exception {
        mvc.perform(get("/solicitante/" + solicitacaoId))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("exame.pdf")));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void operadorVeODetalheDaTriagemComAnexosSemErro500() throws Exception {
        mvc.perform(get("/processos/solicitacoes-online/" + solicitacaoId))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("exame.pdf")));
    }

    @Test
    void queryDeDetalheJaTrazAnexosEUsuarioInicializados() {
        SolicitacaoOnline s = solicitacaoRepo.findParaDetalhe(solicitacaoId).orElseThrow();
        // Fora de transacao: so nao estoura LazyInitializationException se o
        // fetch join tiver funcionado.
        assertThat(s.getAnexos()).hasSize(1);
        assertThat(s.getAnexos().get(0).getNomeArquivo()).isEqualTo("exame.pdf");
        assertThat(s.getUsuarioSolicitante().getUsername()).isEqualTo("solicitante-it");
        assertThat(s.getProcessoGerado()).isNull();
    }
}
