package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.Perfil;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import br.gov.saude.sgpur.service.AuditoriaService;
import br.gov.saude.sgpur.service.UsuarioService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Testes de camada HTTP do UsuarioController (cadastro/gestao de logins,
 * troca da propria senha, ativar/desativar/excluir e "esqueci minha senha").
 * A logica de negocio (auto-lockout do ultimo ADMIN, auto-gerenciamento,
 * rate-limit de reset) ja e coberta em UsuarioServiceTest - aqui o foco e
 * bind de request, view/redirect corretos, flash attributes e como as
 * IllegalArgumentException/IllegalStateException do servico viram flash de
 * erro em vez de 500. Restricao de role por URL e responsabilidade do
 * SecurityConfig, ja coberta em SecurityIntegrationTest (@SpringBootTest) -
 * nao repetida aqui (nao funciona de forma confiavel dentro do slice
 * @WebMvcTest).
 */
@WebMvcTest(UsuarioController.class)
class UsuarioControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean private UsuarioService service;
    @MockitoBean private AuditoriaService auditoria;
    @MockitoBean private MembroUrgenciaRenalRepository membroRepo;
    // Nao usados diretamente pelo UsuarioController, mas exigidos pelo
    // GlobalModelAdvice (@ControllerAdvice global carregado em qualquer
    // slice @WebMvcTest) - sem eles o contexto falha ao subir.
    @MockitoBean private UsuarioRepository usuarioRepository;
    @MockitoBean private ParecerRepository parecerRepository;

    @BeforeEach
    void setUp() {
        when(membroRepo.findByAtivoTrueOrderByInstituicaoAsc()).thenReturn(List.of());
    }

    private Usuario usuario(Long id, String username, Perfil perfil) {
        Usuario u = new Usuario();
        u.setId(id);
        u.setUsername(username);
        u.setNome("Nome " + username);
        u.setEmail(username + "@example.com");
        u.setPerfil(perfil);
        u.setAtivo(true);
        return u;
    }

    // ---- listar / novo / editar ----

    @Test
    @WithMockUser(roles = "ADMIN")
    void listarExibeUsuariosDoServico() throws Exception {
        List<Usuario> usuarios = List.of(usuario(1L, "admin", Perfil.ADMIN), usuario(2L, "operador1", Perfil.OPERADOR));
        when(service.listar()).thenReturn(usuarios);

        mvc.perform(get("/usuarios"))
            .andExpect(status().isOk())
            .andExpect(view().name("usuarios/lista"))
            .andExpect(model().attribute("usuarios", usuarios));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void novoExibeFormularioComUsuarioNovoENaoEdicao() throws Exception {
        mvc.perform(get("/usuarios/novo"))
            .andExpect(status().isOk())
            .andExpect(view().name("usuarios/form"))
            .andExpect(model().attribute("edicao", false))
            .andExpect(model().attribute("usuario", instanceOf(Usuario.class)));

        verify(membroRepo).findByAtivoTrueOrderByInstituicaoAsc();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void editarExibeFormularioComUsuarioExistenteEEdicaoVerdadeira() throws Exception {
        Usuario existente = usuario(5L, "operador1", Perfil.OPERADOR);
        when(service.buscar(5L)).thenReturn(existente);

        mvc.perform(get("/usuarios/5/editar"))
            .andExpect(status().isOk())
            .andExpect(view().name("usuarios/form"))
            .andExpect(model().attribute("edicao", true))
            .andExpect(model().attribute("usuario", existente));
    }

    // ---- criar (POST /usuarios) ----

    @Test
    @WithMockUser(roles = "ADMIN")
    void criarComDadosValidosRedirecionaComFlashMsgERegistraAuditoria() throws Exception {
        mvc.perform(post("/usuarios")
                .with(csrf())
                .param("username", "novo1")
                .param("nome", "Novo Usuario")
                .param("email", "novo1@example.com")
                .param("perfil", "OPERADOR")
                .param("senha", "segredo123"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/usuarios"))
            .andExpect(flash().attribute("msg", "Usuario criado."));

        verify(service).criar(any(Usuario.class), eq("segredo123"), isNull());
        verify(auditoria).registrar(eq("USUARIO_CRIADO"), eq("Usuario novo1"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void criarComSenhaEmBrancoRetornaFormularioComErroDeCampo() throws Exception {
        mvc.perform(post("/usuarios")
                .with(csrf())
                .param("username", "novo1")
                .param("nome", "Novo Usuario")
                .param("email", "novo1@example.com")
                .param("perfil", "OPERADOR")
                .param("senha", ""))
            .andExpect(status().isOk())
            .andExpect(view().name("usuarios/form"))
            .andExpect(model().attribute("edicao", false))
            .andExpect(model().attributeHasFieldErrors("usuario", "senha"));

        verify(service, never()).criar(any(), anyString(), any());
        verifyNoInteractions(auditoria);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void criarSemEmailRetornaFormularioComErroDeCampo() throws Exception {
        mvc.perform(post("/usuarios")
                .with(csrf())
                .param("username", "novo1")
                .param("nome", "Novo Usuario")
                .param("email", "")
                .param("perfil", "OPERADOR")
                .param("senha", "segredo123"))
            .andExpect(status().isOk())
            .andExpect(view().name("usuarios/form"))
            .andExpect(model().attributeHasFieldErrors("usuario", "email"));

        verify(service, never()).criar(any(), anyString(), any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void criarComLoginDuplicadoExibeErroDoServicoSemRedirecionar() throws Exception {
        doThrow(new IllegalArgumentException("Ja existe um usuario com este login."))
            .when(service).criar(any(Usuario.class), anyString(), isNull());

        mvc.perform(post("/usuarios")
                .with(csrf())
                .param("username", "duplicado")
                .param("nome", "Duplicado")
                .param("email", "duplicado@example.com")
                .param("perfil", "OPERADOR")
                .param("senha", "segredo123"))
            .andExpect(status().isOk())
            .andExpect(view().name("usuarios/form"))
            .andExpect(model().attribute("edicao", false))
            .andExpect(model().attribute("erro", "Ja existe um usuario com este login."));

        verifyNoInteractions(auditoria);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void criarSemTokenCsrfEhRejeitado() throws Exception {
        mvc.perform(post("/usuarios")
                .param("username", "novo1")
                .param("nome", "Novo Usuario")
                .param("email", "novo1@example.com")
                .param("perfil", "OPERADOR")
                .param("senha", "segredo123"))
            .andExpect(status().isForbidden());

        verify(service, never()).criar(any(), anyString(), any());
    }

    // ---- atualizar (POST /usuarios/{id}/editar) ----

    @Test
    @WithMockUser(roles = "ADMIN")
    void atualizarComDadosValidosRedirecionaComFlashMsgERegistraAuditoria() throws Exception {
        mvc.perform(post("/usuarios/5/editar")
                .with(csrf())
                .param("username", "editado1")
                .param("nome", "Editado")
                .param("email", "editado1@example.com")
                .param("perfil", "OPERADOR"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/usuarios"))
            .andExpect(flash().attribute("msg", "Usuario atualizado."));

        verify(service).atualizar(eq(5L), any(Usuario.class), isNull(), isNull());
        verify(auditoria).registrar(eq("USUARIO_EDITADO"), eq("Usuario id 5"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void atualizarSemEmailRetornaFormularioComEdicaoVerdadeira() throws Exception {
        mvc.perform(post("/usuarios/5/editar")
                .with(csrf())
                .param("username", "editado1")
                .param("nome", "Editado")
                .param("email", "")
                .param("perfil", "OPERADOR"))
            .andExpect(status().isOk())
            .andExpect(view().name("usuarios/form"))
            .andExpect(model().attribute("edicao", true))
            .andExpect(model().attributeHasFieldErrors("usuario", "email"));

        verify(service, never()).atualizar(any(), any(), any(), any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void atualizarComLoginDuplicadoRedirecionaParaEdicaoComFlashErro() throws Exception {
        doThrow(new IllegalArgumentException("Ja existe um usuario com este login."))
            .when(service).atualizar(eq(9L), any(Usuario.class), isNull(), isNull());

        mvc.perform(post("/usuarios/9/editar")
                .with(csrf())
                .param("username", "conflitante")
                .param("nome", "Conflitante")
                .param("email", "conflitante@example.com")
                .param("perfil", "OPERADOR"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/usuarios/9/editar"))
            .andExpect(flash().attribute("erro", "Ja existe um usuario com este login."));

        verifyNoInteractions(auditoria);
    }

    // ---- alternar-ativo (POST /usuarios/{id}/alternar-ativo) ----

    @Test
    @WithMockUser(username = "admin1", roles = "ADMIN")
    void alternarAtivoComSucessoRedirecionaComFlashMsg() throws Exception {
        mvc.perform(post("/usuarios/3/alternar-ativo").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/usuarios"))
            .andExpect(flash().attribute("msg", "Situacao do usuario atualizada."));

        verify(service).alternarAtivo(3L, "admin1");
    }

    @Test
    @WithMockUser(username = "admin1", roles = "ADMIN")
    void alternarAtivoBloqueadoPorAutoGerenciamentoExibeFlashErroSemRedirecionarParaErro500() throws Exception {
        // Regra real do servico: o proprio usuario logado nao pode se desativar.
        doThrow(new IllegalStateException(
                "Voce nao pode desativar a propria conta. Para trocar sua senha, use 'Minha senha'."))
            .when(service).alternarAtivo(1L, "admin1");

        mvc.perform(post("/usuarios/1/alternar-ativo").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/usuarios"))
            .andExpect(flash().attribute("erro", containsString("propria conta")));
    }

    @Test
    @WithMockUser(username = "admin1", roles = "ADMIN")
    void alternarAtivoBloqueadoPorUltimoAdminAtivoExibeFlashErro() throws Exception {
        doThrow(new IllegalStateException("Nao e possivel desativar o unico administrador ativo do sistema."))
            .when(service).alternarAtivo(4L, "admin1");

        mvc.perform(post("/usuarios/4/alternar-ativo").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/usuarios"))
            .andExpect(flash().attribute("erro", containsString("unico administrador ativo")));
    }

    // ---- excluir (POST /usuarios/{id}/excluir) ----

    @Test
    @WithMockUser(username = "admin1", roles = "ADMIN")
    void excluirComSucessoRedirecionaComFlashMsgERegistraAuditoria() throws Exception {
        mvc.perform(post("/usuarios/7/excluir").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/usuarios"))
            .andExpect(flash().attribute("msg", "Usuario excluido."));

        verify(service).excluir(7L, "admin1");
        verify(auditoria).registrar(eq("USUARIO_EXCLUIDO"), eq("Usuario id 7"));
    }

    @Test
    @WithMockUser(username = "admin1", roles = "ADMIN")
    void excluirBloqueadoPorAutoGerenciamentoExibeFlashErroSemAuditar() throws Exception {
        doThrow(new IllegalStateException(
                "Voce nao pode excluir a propria conta. Para trocar sua senha, use 'Minha senha'."))
            .when(service).excluir(1L, "admin1");

        mvc.perform(post("/usuarios/1/excluir").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/usuarios"))
            .andExpect(flash().attribute("erro", containsString("propria conta")));

        verify(auditoria, never()).registrar(eq("USUARIO_EXCLUIDO"), anyString());
    }

    @Test
    @WithMockUser(username = "admin1", roles = "ADMIN")
    void excluirBloqueadoPorUltimoAdminAtivoExibeFlashErroSemAuditar() throws Exception {
        doThrow(new IllegalStateException("Nao e possivel excluir o unico administrador ativo do sistema."))
            .when(service).excluir(4L, "admin1");

        mvc.perform(post("/usuarios/4/excluir").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/usuarios"))
            .andExpect(flash().attribute("erro", containsString("unico administrador ativo")));

        verify(auditoria, never()).registrar(eq("USUARIO_EXCLUIDO"), anyString());
    }

    // ---- minha-senha ----

    @Test
    @WithMockUser(roles = "OPERADOR")
    void minhaSenhaExibeFormulario() throws Exception {
        mvc.perform(get("/usuarios/minha-senha"))
            .andExpect(status().isOk())
            .andExpect(view().name("usuarios/minha-senha"));
    }

    @Test
    @WithMockUser(username = "operador1", roles = "OPERADOR")
    void trocarMinhaSenhaComSucessoRedirecionaComFlashMsgERegistraAuditoria() throws Exception {
        mvc.perform(post("/usuarios/minha-senha")
                .with(csrf())
                .param("senhaAtual", "atual123")
                .param("novaSenha", "novaSenha123")
                .param("confirmacao", "novaSenha123"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/usuarios/minha-senha"))
            .andExpect(flash().attribute("msg", "Senha alterada com sucesso."));

        verify(service).alterarPropriaSenha("operador1", "atual123", "novaSenha123", "novaSenha123");
        verify(auditoria).registrar(eq("SENHA_ALTERADA"), eq("Usuario operador1"));
    }

    @Test
    @WithMockUser(username = "operador1", roles = "OPERADOR")
    void trocarMinhaSenhaComSenhaAtualIncorretaExibeFlashErroSemAuditar() throws Exception {
        doThrow(new IllegalArgumentException("Senha atual incorreta."))
            .when(service).alterarPropriaSenha(eq("operador1"), anyString(), anyString(), anyString());

        mvc.perform(post("/usuarios/minha-senha")
                .with(csrf())
                .param("senhaAtual", "errada")
                .param("novaSenha", "novaSenha123")
                .param("confirmacao", "novaSenha123"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/usuarios/minha-senha"))
            .andExpect(flash().attribute("erro", "Senha atual incorreta."));

        verifyNoInteractions(auditoria);
    }

    // ---- esqueci-senha ----

    @Test
    @WithMockUser(roles = "OPERADOR")
    void esqueciSenhaExibeFormulario() throws Exception {
        mvc.perform(get("/usuarios/esqueci-senha"))
            .andExpect(status().isOk())
            .andExpect(view().name("usuarios/esqueci-senha"));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void redefinirSenhaSempreExibeMensagemNeutraIndependenteDoUsuarioExistir() throws Exception {
        mvc.perform(post("/usuarios/esqueci-senha")
                .with(csrf())
                .param("username", "qualquerLogin"))
            .andExpect(status().isOk())
            .andExpect(view().name("usuarios/esqueci-senha"))
            .andExpect(model().attribute("sucesso", true))
            .andExpect(model().attribute("msgRedefinicao", containsString("Se o login existir")));

        verify(service).resetarSenha("qualquerLogin");
        verify(auditoria).registrar(eq("SENHA_RESET_SOLICITADO"), eq("Usuario qualquerLogin"));
    }
}
