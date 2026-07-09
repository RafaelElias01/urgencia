package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.*;
import br.gov.saude.sgpur.repository.AnexoRepository;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import br.gov.saude.sgpur.service.AnexoStorageService;
import br.gov.saude.sgpur.service.AuditoriaService;
import br.gov.saude.sgpur.service.DecisaoFinalService;
import br.gov.saude.sgpur.service.ProcessoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Testes do AvaliadorController:
 * - Medico so vota no seu proprio processo.
 * - 403 para processo alheio ou parecer ja emitido.
 * - Voto grava origem/dataHoraVoto/votadoPor corretamente.
 */
@WebMvcTest(AvaliadorController.class)
class AvaliadorControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean private UsuarioRepository usuarioRepo;
    @MockitoBean private ParecerRepository parecerRepo;
    @MockitoBean private AnexoRepository anexoRepo;
    @MockitoBean private AnexoStorageService anexoStorage;
    @MockitoBean private ProcessoService processoService;
    @MockitoBean private DecisaoFinalService decisaoFinalService;
    @MockitoBean private AuditoriaService auditoria;

    private MembroUrgenciaRenal membro;
    private Usuario usuario;
    private Processo processo;
    private Parecer parecer;

    @BeforeEach
    void setUp() {
        membro = new MembroUrgenciaRenal("HCPA", "Veronica Horbe", "veronica@hcpa.edu.br");
        membro.setId(10L);

        usuario = new Usuario();
        usuario.setUsername("avaliador1");
        usuario.setPerfil(Perfil.AVALIADOR);
        usuario.setMembro(membro);

        processo = new Processo();
        processo.setId(1L);
        processo.setNumero("01/2026");
        processo.setPacienteNome("Maria Rosa Silva");
        processo.setStatus(StatusProcesso.ENVIADO);

        parecer = new Parecer(membro);
        parecer.setId(100L);
        parecer.setProcesso(processo);
        parecer.setDataEnvio(LocalDate.now());
        // resultado null = pendente
    }

    @Test
    @WithMockUser(username = "avaliador1", roles = "AVALIADOR")
    void listaProcessosPendentesSemNomeCompleto() throws Exception {
        when(usuarioRepo.findByUsername("avaliador1")).thenReturn(Optional.of(usuario));
        when(parecerRepo.findByMembroIdAndResultadoIsNullAndDataEnvioIsNotNull(10L))
            .thenReturn(List.of(parecer));
        when(anexoRepo.findByProcessoIdAndTipo(1L, TipoAnexo.SOLICITACAO_AVALIADOR))
            .thenReturn(List.of());

        mvc.perform(get("/avaliador"))
            .andExpect(status().isOk())
            .andExpect(view().name("avaliador/lista"))
            // Iniciais M.R.S. devem aparecer no model
            .andExpect(model().attributeExists("iniciaisPorProcesso"))
            // Nome completo NAO deve aparecer na resposta renderizada
            .andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("Maria Rosa Silva"))));
    }

    @Test
    @WithMockUser(username = "avaliador1", roles = "AVALIADOR")
    void painelExibeContadoresEHistoricoDoMembroLogado() throws Exception {
        when(usuarioRepo.findByUsername("avaliador1")).thenReturn(Optional.of(usuario));
        when(parecerRepo.findByMembroIdAndResultadoIsNullAndDataEnvioIsNotNull(10L))
            .thenReturn(List.of(parecer)); // 1 pendente
        when(anexoRepo.findByProcessoIdAndTipo(any(Long.class), any()))
            .thenReturn(List.of());

        // Contadores do repo (apenas do membro logado)
        when(parecerRepo.countByMembroId(10L)).thenReturn(5L);
        when(parecerRepo.countByMembroIdAndResultadoNotNull(10L)).thenReturn(4L);
        when(parecerRepo.countByMembroIdAndResultado(10L, ResultadoParecer.FAVORAVEL))
            .thenReturn(2L);
        when(parecerRepo.countByMembroIdAndResultado(10L, ResultadoParecer.NAO_FAVORAVEL))
            .thenReturn(1L);
        when(parecerRepo.countByMembroIdAndResultado(10L, ResultadoParecer.SOLICITA_INFORMACAO))
            .thenReturn(1L);

        // Historico: parecer ja votado deste membro
        Processo outro = new Processo();
        outro.setId(7L);
        outro.setNumero("07/2026");
        outro.setPacienteNome("Joao Pedro Alves");
        Parecer votado = new Parecer(membro);
        votado.setId(200L);
        votado.setProcesso(outro);
        votado.setResultado(ResultadoParecer.FAVORAVEL);
        votado.setDataResposta(LocalDate.now());
        when(parecerRepo.findByMembroIdAndResultadoIsNotNullOrderByDataRespostaDesc(10L))
            .thenReturn(List.of(votado));

        mvc.perform(get("/avaliador"))
            .andExpect(status().isOk())
            .andExpect(view().name("avaliador/lista"))
            .andExpect(model().attribute("totalAtribuidos", 5L))
            .andExpect(model().attribute("totalPendentes", 1))
            .andExpect(model().attribute("totalAvaliados", 4L))
            .andExpect(model().attribute("favoraveis", 2L))
            .andExpect(model().attribute("naoFavoraveis", 1L))
            .andExpect(model().attribute("solicitaInfo", 1L))
            .andExpect(model().attribute("historico", List.of(votado)))
            // Historico usa apenas iniciais — nunca nome completo
            .andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("Joao Pedro Alves"))));

        // O historico do portal so consulta o membro logado (10L)
        verify(parecerRepo).findByMembroIdAndResultadoIsNotNullOrderByDataRespostaDesc(10L);
    }

    @Test
    @WithMockUser(username = "avaliador1", roles = "AVALIADOR")
    void contagemDePendentesIgnoraVotadosEProcessosNaoAtivos() throws Exception {
        when(usuarioRepo.findByUsername("avaliador1")).thenReturn(Optional.of(usuario));

        // Pendente valido (processo ENVIADO, sem resultado)
        Parecer pendenteAtivo = new Parecer(membro);
        pendenteAtivo.setProcesso(processo); // status ENVIADO
        pendenteAtivo.setDataEnvio(LocalDate.now());

        // Processo ja decidido — nao conta mesmo sem resultado no parecer
        Processo decidido = new Processo();
        decidido.setId(2L);
        decidido.setStatus(StatusProcesso.DEFERIDO);
        Parecer pendenteInativo = new Parecer(membro);
        pendenteInativo.setProcesso(decidido);
        pendenteInativo.setDataEnvio(LocalDate.now());

        // O repositorio ja filtra resultado nulo + dataEnvio nao nula; o filtro de
        // status (ENVIADO/EM_ANALISE) acontece no controller/advice.
        when(parecerRepo.findByMembroIdAndResultadoIsNullAndDataEnvioIsNotNull(10L))
            .thenReturn(List.of(pendenteAtivo, pendenteInativo));
        when(anexoRepo.findByProcessoIdAndTipo(any(Long.class), any()))
            .thenReturn(List.of());

        // So 1 dos 2 deve ser contado (o do processo ativo)
        mvc.perform(get("/avaliador"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("pendentesAvaliador", 1));
    }

    @Test
    @WithMockUser(username = "operador1", roles = "OPERADOR")
    void contagemDePendentesNaoConsultaRepositorioParaNaoAvaliador() throws Exception {
        // Sem ROLE_AVALIADOR o advice deve curto-circuitar: o badge nao e calculado
        // e o repositorio de pareceres NAO e consultado para a contagem.
        // (a propria rota /avaliador rejeita o OPERADOR; o ponto e o advice global)
        mvc.perform(get("/avaliador"));

        verify(parecerRepo, never())
            .findByMembroIdAndResultadoIsNullAndDataEnvioIsNotNull(any());
    }

    @Test
    @WithMockUser(username = "avaliador1", roles = "AVALIADOR")
    void votarExibe403ParaProcessoAlheio() throws Exception {
        when(usuarioRepo.findByUsername("avaliador1")).thenReturn(Optional.of(usuario));
        // Processo 99 nao tem parecer deste membro
        when(parecerRepo.findByProcessoIdAndMembroId(99L, 10L)).thenReturn(Optional.empty());

        mvc.perform(get("/avaliador/99"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "avaliador1", roles = "AVALIADOR")
    void votarExibe403QuandoParecerJaEmitido() throws Exception {
        parecer.setResultado(ResultadoParecer.FAVORAVEL); // ja votou
        when(usuarioRepo.findByUsername("avaliador1")).thenReturn(Optional.of(usuario));
        when(parecerRepo.findByProcessoIdAndMembroId(1L, 10L)).thenReturn(Optional.of(parecer));

        mvc.perform(get("/avaliador/1"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "avaliador1", roles = "AVALIADOR")
    void registrarVotoGravaCamposDeNaoRepudio() throws Exception {
        when(usuarioRepo.findByUsername("avaliador1")).thenReturn(Optional.of(usuario));
        when(parecerRepo.findByProcessoIdAndMembroId(1L, 10L)).thenReturn(Optional.of(parecer));
        when(parecerRepo.save(any(Parecer.class))).thenAnswer(inv -> inv.getArgument(0));
        when(processoService.atualizarStatusPorPareceres(1L)).thenReturn(processo);
        when(processoService.tentarDecisaoAutomatica(1L)).thenReturn(processo);
        doNothing().when(auditoria).registrar(any(), any(), any());

        mvc.perform(post("/avaliador/1/votar")
                .with(csrf())
                .param("resultado", "FAVORAVEL")
                .param("justificativa", "Clinicamente indicado"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/avaliador"));

        // Verifica que os campos de nao-repudio foram gravados
        verify(parecerRepo).save(argThat(p ->
            p.getResultado() == ResultadoParecer.FAVORAVEL
            && p.getOrigem() == OrigemParecer.AVALIADOR_SISTEMA
            && p.getVotadoPor().equals("avaliador1")
            && p.getDataHoraVoto() != null
            && p.getDataResposta() != null
        ));
        verify(processoService).atualizarStatusPorPareceres(1L);
        verify(auditoria).registrar(eq("PARECER_VOTADO"), any(), any());
    }

    @Test
    @WithMockUser(username = "avaliador1", roles = "AVALIADOR")
    void registrarVotoPersisteJustificativa() throws Exception {
        when(usuarioRepo.findByUsername("avaliador1")).thenReturn(Optional.of(usuario));
        when(parecerRepo.findByProcessoIdAndMembroId(1L, 10L)).thenReturn(Optional.of(parecer));
        when(parecerRepo.save(any(Parecer.class))).thenAnswer(inv -> inv.getArgument(0));
        when(processoService.atualizarStatusPorPareceres(1L)).thenReturn(processo);
        when(processoService.tentarDecisaoAutomatica(1L)).thenReturn(processo);
        doNothing().when(auditoria).registrar(any(), any(), any());

        mvc.perform(post("/avaliador/1/votar")
                .with(csrf())
                .param("resultado", "FAVORAVEL")
                .param("justificativa", "  Quadro clinico compativel  "))
            .andExpect(status().is3xxRedirection());

        // Justificativa salva com trim aplicado
        verify(parecerRepo).save(argThat(p ->
            "Quadro clinico compativel".equals(p.getJustificativa())));
    }

    @Test
    @WithMockUser(username = "avaliador1", roles = "AVALIADOR")
    void registrarVotoJustificativaVaziaViraNull() throws Exception {
        when(usuarioRepo.findByUsername("avaliador1")).thenReturn(Optional.of(usuario));
        when(parecerRepo.findByProcessoIdAndMembroId(1L, 10L)).thenReturn(Optional.of(parecer));
        when(parecerRepo.save(any(Parecer.class))).thenAnswer(inv -> inv.getArgument(0));
        when(processoService.atualizarStatusPorPareceres(1L)).thenReturn(processo);
        when(processoService.tentarDecisaoAutomatica(1L)).thenReturn(processo);
        doNothing().when(auditoria).registrar(any(), any(), any());

        mvc.perform(post("/avaliador/1/votar")
                .with(csrf())
                .param("resultado", "NAO_FAVORAVEL")
                .param("justificativa", "   "))
            .andExpect(status().is3xxRedirection());

        // Justificativa em branco nao deve ser persistida (null)
        verify(parecerRepo).save(argThat(p -> p.getJustificativa() == null));
    }

    @Test
    @WithMockUser(username = "avaliador1", roles = "AVALIADOR")
    void registrarVotoExibe403QuandoParecerJaEmitido() throws Exception {
        parecer.setResultado(ResultadoParecer.NAO_FAVORAVEL); // ja votou
        when(usuarioRepo.findByUsername("avaliador1")).thenReturn(Optional.of(usuario));
        when(parecerRepo.findByProcessoIdAndMembroId(1L, 10L)).thenReturn(Optional.of(parecer));

        mvc.perform(post("/avaliador/1/votar")
                .with(csrf())
                .param("resultado", "FAVORAVEL"))
            .andExpect(status().isForbidden());

        // Nao deve ter salvo nada
        verify(parecerRepo, never()).save(any());
    }

    @Test
    @WithMockUser(username = "avaliador1", roles = "AVALIADOR")
    void registrarVotoExibe403QuandoProcessoNaoEstaEmEnvio() throws Exception {
        processo.setStatus(StatusProcesso.DEFERIDO); // processo ja decidido
        when(usuarioRepo.findByUsername("avaliador1")).thenReturn(Optional.of(usuario));
        when(parecerRepo.findByProcessoIdAndMembroId(1L, 10L)).thenReturn(Optional.of(parecer));

        mvc.perform(post("/avaliador/1/votar")
                .with(csrf())
                .param("resultado", "FAVORAVEL"))
            .andExpect(status().isForbidden());

        verify(parecerRepo, never()).save(any());
    }
}
