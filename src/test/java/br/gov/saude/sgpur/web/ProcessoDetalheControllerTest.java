package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.*;
import br.gov.saude.sgpur.service.MembroUrgenciaRenalService;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import br.gov.saude.sgpur.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.Year;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Testes do ProcessoDetalheController: criacao (sempre a partir de uma
 * SolicitacaoOnline - ajuste de 2026-07-27 -, numeracao automatica vs
 * manual, validacoes), a tela de detalhe (gating das abas 1-5 e o
 * sub-rotulo de status), edicao, reabertura e exclusao. Recebimento (passo
 * 1) e sempre automatico e nao tem mais endpoint proprio.
 */
@WebMvcTest(ProcessoDetalheController.class)
class ProcessoDetalheControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean private ProcessoService processoService;
    @MockitoBean private FluxoProcessoService fluxoService;
    @MockitoBean private EmailTemplateService emailTemplateService;
    @MockitoBean private MembroUrgenciaRenalService membroService;
    @MockitoBean private AnexoStorageService anexoStorage;
    @MockitoBean private AuditoriaService auditoria;
    @MockitoBean private GeminiService geminiService;
    @MockitoBean private ConflitoEquipeMatcher conflitoEquipeMatcher;
    @MockitoBean private br.gov.saude.sgpur.service.SolicitacaoOnlineService solicitacaoOnlineService;
    @MockitoBean private br.gov.saude.sgpur.repository.SolicitacaoOnlineRepository solicitacaoOnlineRepository;
    // GlobalModelAdvice (@ControllerAdvice global) precisa dessas duas pro
    // contexto do @WebMvcTest subir - ver ArquivoControllerTest.
    @MockitoBean private UsuarioRepository usuarioRepository;
    @MockitoBean private ParecerRepository parecerRepository;
    @MockitoBean private MensagemSolicitacaoService mensagemSolicitacaoService;
    @MockitoBean private br.gov.saude.sgpur.repository.AnexoRepository anexoRepository;

    private Processo processo;

    @BeforeEach
    void setUp() {
        processo = new Processo();
        processo.setId(1L);
        processo.setNumero("01/2026");
        processo.setPacienteNome("Maria Silva");
        processo.setSolicitanteEquipe("Equipe A");
        processo.setStatus(StatusProcesso.ENVIADO);
        when(processoService.buscar(1L)).thenReturn(processo);
        when(geminiService.isDisponivel()).thenReturn(false);
        when(emailTemplateService.gerar(any())).thenReturn(List.of());
        when(conflitoEquipeMatcher.mesmaEquipe(any(), any())).thenReturn(false);
        // Padrao "sem passos" causaria IndexOutOfBounds no detalhe() (usa
        // passosWizard.get(size-1)) - cada teste que chama GET /processos/1
        // sobrescreve com uma lista nao-vazia quando precisar.
        when(fluxoService.montarEtapas(any())).thenReturn(List.of());
        when(fluxoService.montarPassosWizard(any())).thenReturn(List.of(
            new PassoWizard(1, "Recebimento", "recebimento", PassoWizard.Estado.ATUAL, "")));
        // Gating/subrotulo agora vem de FluxoProcessoService (extraido do
        // controller) - como o service e mockado aqui, o default e "nada
        // liberado alem do recebimento" e sem subrotulo; cada teste que
        // precisa de outro cenario sobrescreve com o stub especifico.
        when(fluxoService.calcularGating(any())).thenReturn(
            new FluxoProcessoService.GatingAbas(true, false, false, false, false));
        when(fluxoService.calcularSubrotuloStatus(any())).thenReturn(null);
        // Todo processo hoje vem do Portal do Solicitante; testes especificos
        // de detalhe sobrescrevem quando precisarem simular um processo
        // legado sem esse vinculo.
        when(fluxoService.veioDoPortal(any())).thenReturn(true);
    }

    /** SolicitacaoOnline valida (status ENVIADA, ainda nao triada) para os testes de novo/salvar. */
    private SolicitacaoOnline solicitacaoValida(Long id) {
        SolicitacaoOnline s = new SolicitacaoOnline();
        s.setId(id);
        s.setPacienteNome("Maria Silva");
        s.setPacienteRgct("RGCT123");
        s.setSolicitanteEquipe("Equipe A");
        s.setSolicitanteEmail("equipe@ex.com");
        s.setDataSituacaoEspecial(LocalDate.of(2026, 7, 1));
        s.setJustificativaClinica("Justificativa");
        return s;
    }

    private static MembroUrgenciaRenal membro(Long id, String instituicao, String nome) {
        MembroUrgenciaRenal m = new MembroUrgenciaRenal(instituicao, nome, nome.toLowerCase() + "@ex.com");
        m.setId(id);
        return m;
    }

    private static Parecer parecer(Processo p, MembroUrgenciaRenal m, ResultadoParecer resultado,
                                    LocalDate dataEnvio, OrigemParecer origem) {
        Parecer par = new Parecer(m);
        par.setProcesso(p);
        par.setResultado(resultado);
        par.setDataEnvio(dataEnvio);
        par.setOrigem(origem);
        return par;
    }

    // ----- novo -----

    @Test
    @WithMockUser(roles = "OPERADOR")
    void novoSemOrigemSolicitacaoOnlineRedirecionaParaTriagem() throws Exception {
        // Desde 2026-07-27 nao ha mais cadastro manual "do zero" - sem o
        // parametro, redireciona para a fila de triagem em vez de abrir o form.
        mvc.perform(get("/processos/novo"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/processos/solicitacoes-online"))
            .andExpect(flash().attribute("erro", org.hamcrest.Matchers.containsString("Portal do Solicitante")));

        verify(processoService, never()).isNumeracaoAutomatica(anyInt());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void novoComSolicitacaoJaTriadaRedirecionaParaODetalheDela() throws Exception {
        SolicitacaoOnline s = solicitacaoValida(5L);
        s.setStatus(StatusSolicitacaoOnline.CONVERTIDA);
        when(solicitacaoOnlineService.buscar(5L)).thenReturn(s);

        mvc.perform(get("/processos/novo").param("origemSolicitacaoOnlineId", "5"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/processos/solicitacoes-online/5"))
            .andExpect(flash().attribute("erro", org.hamcrest.Matchers.containsString("ja foi triada")));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void novoComNumeracaoAutomaticaNaoSugereNumero() throws Exception {
        int ano = Year.now().getValue();
        when(solicitacaoOnlineService.buscar(5L)).thenReturn(solicitacaoValida(5L));
        when(processoService.isNumeracaoAutomatica(ano)).thenReturn(true);

        mvc.perform(get("/processos/novo").param("origemSolicitacaoOnlineId", "5"))
            .andExpect(status().isOk())
            .andExpect(view().name("processos/form"))
            .andExpect(model().attribute("numeracaoAutomatica", true));

        verify(processoService, never()).proximoNumero(anyInt());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void novoComNumeracaoManualSugereOProximoNumero() throws Exception {
        int ano = Year.now().getValue();
        when(solicitacaoOnlineService.buscar(5L)).thenReturn(solicitacaoValida(5L));
        when(processoService.isNumeracaoAutomatica(ano)).thenReturn(false);
        when(processoService.proximoNumero(ano)).thenReturn("05/" + ano);

        mvc.perform(get("/processos/novo").param("origemSolicitacaoOnlineId", "5"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("numeracaoAutomatica", false));

        verify(processoService).proximoNumero(ano);
    }

    // ----- salvar -----

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder formValido() {
        return post("/processos")
            .param("pacienteNome", "Maria Silva")
            .param("pacienteRgct", "RGCT123")
            .param("solicitanteEquipe", "Equipe A")
            .param("solicitanteEmail", "equipe@ex.com")
            .param("dataSituacaoEspecial", "2026-07-01")
            .param("medicoIds", "1", "2", "3")
            .param("origemSolicitacaoOnlineId", "5")
            .with(csrf());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void salvarSemOrigemSolicitacaoOnlineRedirecionaParaTriagem() throws Exception {
        mvc.perform(post("/processos")
                .param("pacienteNome", "Maria Silva")
                .param("pacienteRgct", "RGCT123")
                .param("solicitanteEquipe", "Equipe A")
                .param("solicitanteEmail", "equipe@ex.com")
                .param("dataSituacaoEspecial", "2026-07-01")
                .param("medicoIds", "1", "2", "3")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/processos/solicitacoes-online"))
            .andExpect(flash().attribute("erro", org.hamcrest.Matchers.containsString("Portal do Solicitante")));

        verify(processoService, never()).cadastrar(any(), any());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void salvarSemNumeroEmNumeracaoManualEhRejeitado() throws Exception {
        when(solicitacaoOnlineService.buscar(5L)).thenReturn(solicitacaoValida(5L));
        when(processoService.isNumeracaoAutomatica(2026)).thenReturn(false);
        when(membroService.listarAtivos()).thenReturn(List.of());

        mvc.perform(formValido())
            .andExpect(status().isOk())
            .andExpect(view().name("processos/form"));

        verify(processoService, never()).cadastrar(any(), any());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void salvarComNumeroEmFormatoInvalidoEhRejeitado() throws Exception {
        when(solicitacaoOnlineService.buscar(5L)).thenReturn(solicitacaoValida(5L));
        when(processoService.isNumeracaoAutomatica(2026)).thenReturn(false);
        when(membroService.listarAtivos()).thenReturn(List.of());

        mvc.perform(formValido().param("numero", "abc"))
            .andExpect(status().isOk())
            .andExpect(view().name("processos/form"));

        verify(processoService, never()).cadastrar(any(), any());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void salvarComNumeroDuplicadoEhRejeitado() throws Exception {
        when(solicitacaoOnlineService.buscar(5L)).thenReturn(solicitacaoValida(5L));
        when(processoService.isNumeracaoAutomatica(2026)).thenReturn(false);
        when(processoService.numeroJaExiste("01/2026")).thenReturn(true);
        when(membroService.listarAtivos()).thenReturn(List.of());

        mvc.perform(formValido().param("numero", "01/2026"))
            .andExpect(status().isOk())
            .andExpect(view().name("processos/form"));

        verify(processoService, never()).cadastrar(any(), any());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void salvarComQuantidadeErradaDeMedicosEhRejeitado() throws Exception {
        when(solicitacaoOnlineService.buscar(5L)).thenReturn(solicitacaoValida(5L));
        when(processoService.isNumeracaoAutomatica(2026)).thenReturn(true);
        when(membroService.listarAtivos()).thenReturn(List.of());

        mvc.perform(post("/processos")
                .param("pacienteNome", "Maria Silva")
                .param("pacienteRgct", "RGCT123")
                .param("solicitanteEquipe", "Equipe A")
                .param("solicitanteEmail", "equipe@ex.com")
                .param("dataSituacaoEspecial", "2026-07-01")
                .param("medicoIds", "1", "2")
                .param("origemSolicitacaoOnlineId", "5")
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(view().name("processos/form"));

        verify(processoService, never()).cadastrar(any(), any());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void salvarComCamposObrigatoriosEmBrancoEhRejeitadoPelaBeanValidation() throws Exception {
        when(solicitacaoOnlineService.buscar(5L)).thenReturn(solicitacaoValida(5L));
        when(processoService.isNumeracaoAutomatica(2026)).thenReturn(true);
        when(membroService.listarAtivos()).thenReturn(List.of());

        mvc.perform(post("/processos")
                .param("dataSituacaoEspecial", "2026-07-01")
                .param("medicoIds", "1", "2", "3")
                .param("origemSolicitacaoOnlineId", "5")
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(view().name("processos/form"));

        verify(processoService, never()).cadastrar(any(), any());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void salvarComDadosValidosCadastraERedireciona() throws Exception {
        when(solicitacaoOnlineService.buscar(5L)).thenReturn(solicitacaoValida(5L));
        when(processoService.isNumeracaoAutomatica(2026)).thenReturn(true);
        Processo salvo = new Processo();
        salvo.setId(9L);
        salvo.setNumero("09/2026");
        salvo.setPacienteNome("Maria Silva");
        when(processoService.cadastrar(any(), eq(List.of(1L, 2L, 3L)))).thenReturn(salvo);

        mvc.perform(formValido())
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/processos/9"))
            .andExpect(flash().attribute("msg", org.hamcrest.Matchers.containsString("09/2026")));

        verify(auditoria).registrar(eq("PROCESSO_CADASTRADO"), anyString());
        verify(solicitacaoOnlineService).converter(eq(5L), eq(salvo));
    }

    // ----- detalhe -----

    @Test
    @WithMockUser(roles = "OPERADOR")
    void detalheDeUmProcessoRecemCriadoNaoLiberaNadaAlemDoRecebimento() throws Exception {
        // Processo sem pareceres/anexos: nada alem do recebimento esta liberado.
        mvc.perform(get("/processos/1"))
            .andExpect(status().isOk())
            .andExpect(view().name("processos/detalhe"))
            .andExpect(model().attribute("liberadoRecebimento", true))
            .andExpect(model().attribute("liberadoEnvio", false))
            .andExpect(model().attribute("liberadoRespostas", false))
            .andExpect(model().attribute("liberadoDecisao", false))
            .andExpect(model().attribute("liberadoFinalizacao", false));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void detalheLiberaEnvioQuandoRecebimentoFoiFeito() throws Exception {
        // Recebimento e sempre automatico desde 2026-07-27 - o controller so
        // repassa o gating calculado por FluxoProcessoService (mockado aqui).
        when(fluxoService.calcularGating(processo)).thenReturn(
            new FluxoProcessoService.GatingAbas(true, true, false, false, false));

        mvc.perform(get("/processos/1"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("liberadoEnvio", true))
            .andExpect(model().attribute("liberadoRespostas", false));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void detalheMostraAguardandoParecerQuandoAindaNaoHaMaioria() throws Exception {
        MembroUrgenciaRenal m1 = membro(1L, "HCPA", "Ana");
        MembroUrgenciaRenal m2 = membro(2L, "HCC", "Bruno");
        MembroUrgenciaRenal m3 = membro(3L, "HSL", "Carla");
        processo.addParecer(parecer(processo, m1, ResultadoParecer.FAVORAVEL,
            LocalDate.now(), OrigemParecer.OPERADOR_EMAIL));
        processo.addParecer(parecer(processo, m2, null, LocalDate.now(), null));
        processo.addParecer(parecer(processo, m3, null, LocalDate.now(), null));
        when(processoService.sugerirDecisao(processo)).thenReturn(Optional.empty());
        when(processoService.contarRespondidos(processo)).thenReturn(1L);
        when(processoService.pareceresRecebidosSemAnexo(processo)).thenReturn(List.of());
        when(fluxoService.calcularSubrotuloStatus(processo)).thenReturn("Aguardando parecer (1/3)");
        when(fluxoService.calcularGating(processo)).thenReturn(
            new FluxoProcessoService.GatingAbas(true, false, false, false, false));

        mvc.perform(get("/processos/1"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("statusSubrotulo", "Aguardando parecer (1/3)"))
            .andExpect(model().attribute("liberadoDecisao", false));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void detalheLiberaDecisaoQuandoMaioriaFormadaESemAnexoPendente() throws Exception {
        // O gating real (FluxoProcessoService) e mockado aqui; o teste so
        // confere que o controller repassa liberadoDecisao=true corretamente.
        MembroUrgenciaRenal m1 = membro(1L, "HCPA", "Ana");
        MembroUrgenciaRenal m2 = membro(2L, "HCC", "Bruno");
        MembroUrgenciaRenal m3 = membro(3L, "HSL", "Carla");
        processo.addParecer(parecer(processo, m1, ResultadoParecer.FAVORAVEL,
            LocalDate.now(), OrigemParecer.OPERADOR_EMAIL));
        processo.addParecer(parecer(processo, m2, ResultadoParecer.FAVORAVEL,
            LocalDate.now(), OrigemParecer.OPERADOR_EMAIL));
        processo.addParecer(parecer(processo, m3, null, LocalDate.now(), null));
        when(processoService.sugerirDecisao(processo)).thenReturn(Optional.of(StatusProcesso.DEFERIDO));
        when(processoService.contarRespondidos(processo)).thenReturn(2L);
        when(processoService.pareceresRecebidosSemAnexo(processo)).thenReturn(List.of());
        when(fluxoService.calcularSubrotuloStatus(processo)).thenReturn(
            "Maioria formada - pronto para decidir (Deferido)");
        when(fluxoService.calcularGating(processo)).thenReturn(
            new FluxoProcessoService.GatingAbas(true, true, true, true, false));

        mvc.perform(get("/processos/1"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("statusSubrotulo",
                "Maioria formada - pronto para decidir (Deferido)"))
            .andExpect(model().attribute("liberadoDecisao", true));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void detalheBloqueiaDecisaoQuandoAguardandoInformacaoComplementar() throws Exception {
        processo.setStatus(StatusProcesso.SOLICITA_INFORMACAO);
        MembroUrgenciaRenal m1 = membro(1L, "HCPA", "Ana");
        processo.addParecer(parecer(processo, m1, ResultadoParecer.FAVORAVEL,
            LocalDate.now(), OrigemParecer.OPERADOR_EMAIL));
        when(processoService.sugerirDecisao(processo)).thenReturn(Optional.empty());
        when(processoService.contarRespondidos(processo)).thenReturn(1L);
        when(processoService.pareceresRecebidosSemAnexo(processo)).thenReturn(List.of());

        mvc.perform(get("/processos/1"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("aguardandoInfo", true))
            .andExpect(model().attribute("liberadoDecisao", false));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void detalheIdentificaPareceresVotadosPeloPortalComoImutaveis() throws Exception {
        MembroUrgenciaRenal m1 = membro(1L, "HCPA", "Ana");
        Parecer votadoPeloPortal = parecer(processo, m1, ResultadoParecer.FAVORAVEL,
            LocalDate.now(), OrigemParecer.AVALIADOR_SISTEMA);
        votadoPeloPortal.setId(100L);
        processo.addParecer(votadoPeloPortal);
        when(processoService.sugerirDecisao(processo)).thenReturn(Optional.empty());
        when(processoService.pareceresRecebidosSemAnexo(processo)).thenReturn(List.of());

        mvc.perform(get("/processos/1"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("pareceresPortal", java.util.Set.of(100L)));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void detalheAvisaSobreMedicoDaMesmaEquipeDoSolicitante() throws Exception {
        MembroUrgenciaRenal m1 = membro(1L, "Equipe A", "Ana");
        processo.addParecer(parecer(processo, m1, null, null, null));
        when(processoService.sugerirDecisao(processo)).thenReturn(Optional.empty());
        when(processoService.pareceresRecebidosSemAnexo(processo)).thenReturn(List.of());
        when(conflitoEquipeMatcher.mesmaEquipe("Equipe A", "Equipe A")).thenReturn(true);

        mvc.perform(get("/processos/1"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("medicosMesmaEquipe", List.of("Ana (Equipe A)")));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void detalheExpoeAsSugestoesEContadoresDoServico() throws Exception {
        when(processoService.sugerirDecisao(processo)).thenReturn(Optional.empty());
        when(processoService.contarFavoraveis(processo)).thenReturn(2L);
        when(processoService.deferidoPeloCoordenador(processo)).thenReturn(true);
        when(processoService.pareceresRecebidosSemAnexo(processo)).thenReturn(List.of());

        mvc.perform(get("/processos/1"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("favoraveis", 2L))
            .andExpect(model().attribute("deferidoPeloCoordenador", true));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void detalheExpoeProcessoVeioDoPortalFalseParaProcessoLegadoSemVinculo() throws Exception {
        // Processo legado (anterior a 2026-07-27) sem SolicitacaoOnline de
        // origem - caso raro hoje, mas veioDoPortal ainda pode retornar false.
        when(fluxoService.veioDoPortal(processo)).thenReturn(false);

        mvc.perform(get("/processos/1"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("processoVeioDoPortal", false))
            .andExpect(model().attribute("solicitacaoOnlineOrigemId", (Object) null));

        verify(solicitacaoOnlineRepository, never()).findIdByProcessoGeradoId(anyLong());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void detalheExpoeProcessoVeioDoPortalTrueELinkDaOrigem() throws Exception {
        // fluxoService.veioDoPortal ja e true por padrao no setUp (todo
        // processo hoje vem do Portal do Solicitante).
        when(solicitacaoOnlineRepository.findIdByProcessoGeradoId(1L)).thenReturn(Optional.of(42L));
        when(fluxoService.calcularGating(processo)).thenReturn(
            new FluxoProcessoService.GatingAbas(true, true, false, false, false));

        mvc.perform(get("/processos/1"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("processoVeioDoPortal", true))
            .andExpect(model().attribute("solicitacaoOnlineOrigemId", 42L))
            .andExpect(model().attribute("liberadoEnvio", true));
    }

    // ----- editar / atualizar -----

    @Test
    @WithMockUser(roles = "OPERADOR")
    void editarCarregaOProcessoNoModel() throws Exception {
        mvc.perform(get("/processos/1/editar"))
            .andExpect(status().isOk())
            .andExpect(view().name("processos/editar"))
            .andExpect(model().attribute("processo", processo));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void editarBloqueadoQuandoProcessoEncerrado() throws Exception {
        when(processoService.edicaoBloqueada(processo)).thenReturn(true);

        mvc.perform(get("/processos/1/editar"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/processos/1"))
            .andExpect(flash().attribute("erro", ProcessoValidator.MSG_ENCERRADO));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void atualizarComErroDeValidacaoVoltaParaOFormulario() throws Exception {
        mvc.perform(post("/processos/1/editar").with(csrf()))
            .andExpect(status().isOk())
            .andExpect(view().name("processos/editar"));

        verify(processoService, never()).atualizarDados(anyLong(), any());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void atualizarBloqueadoQuandoProcessoEncerrado() throws Exception {
        when(processoService.edicaoBloqueada(processo)).thenReturn(true);

        mvc.perform(post("/processos/1/editar")
                .param("numero", "01/2026")
                .param("pacienteNome", "Maria Silva")
                .param("pacienteRgct", "RGCT123")
                .param("solicitanteEquipe", "Equipe A")
                .param("solicitanteEmail", "equipe@ex.com")
                .param("dataSituacaoEspecial", "2026-07-01")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/processos/1"))
            .andExpect(flash().attribute("erro", ProcessoValidator.MSG_ENCERRADO));

        verify(processoService, never()).atualizarDados(anyLong(), any());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void atualizarComSucessoRegistraAuditoriaERedireciona() throws Exception {
        when(processoService.edicaoBloqueada(processo)).thenReturn(false);

        mvc.perform(post("/processos/1/editar")
                .param("numero", "01/2026")
                .param("pacienteNome", "Maria Silva Atualizada")
                .param("pacienteRgct", "RGCT123")
                .param("solicitanteEquipe", "Equipe A")
                .param("solicitanteEmail", "equipe@ex.com")
                .param("dataSituacaoEspecial", "2026-07-01")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/processos/1"))
            .andExpect(flash().attribute("msg", "Processo atualizado."));

        verify(processoService).atualizarDados(eq(1L), any());
        verify(auditoria).registrar(eq("PROCESSO_EDITADO"), anyString());
    }

    // ----- reabrir -----

    @Test
    @WithMockUser(roles = "ADMIN")
    void reabrirComSucesso() throws Exception {
        mvc.perform(post("/processos/1/reabrir").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/processos/1"))
            .andExpect(flash().attribute("msg", org.hamcrest.Matchers.containsString("reaberto")));

        verify(processoService).reabrir(1L);
        verify(auditoria).registrar(eq("PROCESSO_REABERTO"), anyString());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void reabrirComFalhaDeRegraDeNegocioVoltaFlashDeErro() throws Exception {
        doThrow(new IllegalStateException("Processo nao esta encerrado.")).when(processoService).reabrir(1L);

        mvc.perform(post("/processos/1/reabrir").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("erro", "Processo nao esta encerrado."));
    }

    // ----- excluir -----

    @Test
    @WithMockUser(roles = "ADMIN")
    void excluirBloqueadoQuandoProcessoEncerrado() throws Exception {
        when(processoService.edicaoBloqueada(processo)).thenReturn(true);

        mvc.perform(post("/processos/1/excluir").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/processos/1"))
            .andExpect(flash().attribute("erro", ProcessoValidator.MSG_ENCERRADO));

        verify(processoService, never()).excluir(anyLong());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void excluirComSucessoRemoveAPastaDeAnexos() throws Exception {
        when(processoService.edicaoBloqueada(processo)).thenReturn(false);

        mvc.perform(post("/processos/1/excluir").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/processos"))
            .andExpect(flash().attribute("msg", org.hamcrest.Matchers.containsString("excluido")));

        verify(processoService).excluir(1L);
        verify(anexoStorage).removerPastaProcesso(processo);
    }

    // ----- trava de anonimizacao (documento vindo do Portal do Solicitante) -----

    /**
     * O documento em staging NAO entra na lista de documentos clinicos (a que
     * vai para os avaliadores) e a aba Envio o mostra em bloco proprio,
     * marcado como pendente. Tambem cobre a renderizacao real do template.
     */
    @Test
    @WithMockUser(roles = "OPERADOR")
    void detalheSeparaDocumentoPendenteDeAnonimizacaoDosDocumentosClinicos() throws Exception {
        Anexo pendente = anexoPendente(7L);
        when(fluxoService.calcularGating(processo)).thenReturn(
            new FluxoProcessoService.GatingAbas(true, true, false, false, false));

        mvc.perform(get("/processos/1"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("documentosClinicos", org.hamcrest.Matchers.empty()))
            .andExpect(model().attribute("documentosPendentesAnonimizacao",
                org.hamcrest.Matchers.contains(pendente)))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("pendente de anonimizacao")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                "Confirmo que este documento foi anonimizado")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                "/processos/1/documento-clinico/7/confirmar-anonimizacao")));
    }

    // ----- aba Decisao: textos vs. comportamento real -----

    /**
     * A decisao automatica passou a valer nos DOIS sentidos (2 favoraveis
     * deferem, 2 desfavoraveis indeferem — ver
     * {@code ProcessoService.tentarDecisaoAutomatica}), mas a aba Decisao
     * ainda dizia que so o deferimento era automatico. Este teste renderiza o
     * template de verdade e trava o texto atualizado (inclusive a regra do
     * coordenador e o motivo institucional padrao do indeferimento
     * automatico), que e o que o operador le antes de decidir na mao.
     */
    @Test
    @WithMockUser(roles = "OPERADOR")
    void abaDecisaoExplicaQueOAutomaticoDefereEIndefere() throws Exception {
        MembroUrgenciaRenal m1 = membro(1L, "HCPA", "Ana");
        MembroUrgenciaRenal m2 = membro(2L, "HCC", "Bruno");
        MembroUrgenciaRenal m3 = membro(3L, "HSL", "Carla");
        processo.addParecer(parecer(processo, m1, ResultadoParecer.NAO_FAVORAVEL,
            LocalDate.now(), OrigemParecer.AVALIADOR_SISTEMA));
        processo.addParecer(parecer(processo, m2, ResultadoParecer.NAO_FAVORAVEL,
            LocalDate.now(), OrigemParecer.AVALIADOR_SISTEMA));
        processo.addParecer(parecer(processo, m3, null, LocalDate.now(), null));
        when(processoService.contarRespondidos(processo)).thenReturn(2L);
        when(processoService.pareceresRecebidosSemAnexo(processo)).thenReturn(List.of());
        when(processoService.sugerirDecisao(processo)).thenReturn(Optional.of(StatusProcesso.INDEFERIDO));
        when(fluxoService.calcularGating(processo)).thenReturn(
            new FluxoProcessoService.GatingAbas(true, true, true, true, false));

        mvc.perform(get("/processos/1"))
            .andExpect(status().isOk())
            // sugestao automatica: nao pode falar so de deferimento
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                "maioria simples de 2 em 3 votos, tanto para deferir quanto para")))
            .andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("2 de 3 favoraveis defere o processo"))))
            // formulario manual: quando ainda e necessario
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                "favoravel do coordenador da CET-RS defere sozinho")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                "texto institucional padrao")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                "<strong>Cancelado</strong> (que nunca e")));
    }

    /** Anexo em staging (veio do portal, ainda nao revisado) vinculado ao processo. */
    private Anexo anexoPendente(Long id) {
        Anexo a = new Anexo();
        a.setId(id);
        a.setTipo(TipoAnexo.DOCUMENTO_PORTAL_NAO_ANONIMIZADO);
        a.setNomeArquivo("laudo.pdf");
        a.setContentType("application/pdf");
        processo.addAnexo(a);
        return a;
    }

    @Test
    @WithMockUser(username = "operador1", roles = "OPERADOR")
    void confirmarAnonimizacaoPromoveOTipoERegistraAuditoria() throws Exception {
        Anexo pendente = anexoPendente(7L);
        when(processoService.edicaoBloqueada(processo)).thenReturn(false);

        mvc.perform(post("/processos/1/documento-clinico/7/confirmar-anonimizacao")
                .param("confirmo", "true").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/processos/1#envio"))
            .andExpect(flash().attribute("msg", org.hamcrest.Matchers.containsString("Anonimizacao confirmada")));

        org.assertj.core.api.Assertions.assertThat(pendente.getTipo())
            .isEqualTo(TipoAnexo.DOCUMENTO_CLINICO_AVALIADOR);
        verify(anexoRepository).save(pendente);
        // O log de auditoria e o registro de que a revisao humana aconteceu:
        // precisa dizer QUEM confirmou e QUAL anexo.
        verify(auditoria).registrar(eq("ANONIMIZACAO_CONFIRMADA"),
            org.mockito.ArgumentMatchers.contains("operador1"));
        verify(auditoria).registrar(eq("ANONIMIZACAO_CONFIRMADA"),
            org.mockito.ArgumentMatchers.contains("laudo.pdf"));
    }

    @Test
    @WithMockUser(username = "operador1", roles = "OPERADOR")
    void confirmarAnonimizacaoSemMarcarACaixaNaoPromove() throws Exception {
        Anexo pendente = anexoPendente(7L);
        when(processoService.edicaoBloqueada(processo)).thenReturn(false);

        mvc.perform(post("/processos/1/documento-clinico/7/confirmar-anonimizacao").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("erro", org.hamcrest.Matchers.containsString("Marque a confirmacao")));

        org.assertj.core.api.Assertions.assertThat(pendente.getTipo())
            .isEqualTo(TipoAnexo.DOCUMENTO_PORTAL_NAO_ANONIMIZADO);
        verify(anexoRepository, never()).save(any());
        verify(auditoria, never()).registrar(eq("ANONIMIZACAO_CONFIRMADA"), anyString());
    }

    @Test
    @WithMockUser(username = "operador1", roles = "OPERADOR")
    void confirmarAnonimizacaoBloqueadaQuandoProcessoEncerrado() throws Exception {
        Anexo pendente = anexoPendente(7L);
        when(processoService.edicaoBloqueada(processo)).thenReturn(true);

        mvc.perform(post("/processos/1/documento-clinico/7/confirmar-anonimizacao")
                .param("confirmo", "true").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("erro", ProcessoValidator.MSG_ENCERRADO));

        org.assertj.core.api.Assertions.assertThat(pendente.getTipo())
            .isEqualTo(TipoAnexo.DOCUMENTO_PORTAL_NAO_ANONIMIZADO);
        verify(anexoRepository, never()).save(any());
    }

    /** Anexo de outro processo (ou inexistente) nunca e promovido por ID solto. */
    @Test
    @WithMockUser(username = "operador1", roles = "OPERADOR")
    void confirmarAnonimizacaoDeAnexoForaDoProcessoFalha() throws Exception {
        anexoPendente(7L);
        when(processoService.edicaoBloqueada(processo)).thenReturn(false);

        mvc.perform(post("/processos/1/documento-clinico/999/confirmar-anonimizacao")
                .param("confirmo", "true").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("erro", org.hamcrest.Matchers.containsString("nao encontrado")));

        verify(anexoRepository, never()).save(any());
    }

    /**
     * Idempotencia/coerencia: um documento que ja e material do avaliador
     * (inclusive os de processos LEGADOS, convertidos antes da trava) nao esta
     * "pendente" e nao passa por esta acao.
     */
    @Test
    @WithMockUser(username = "operador1", roles = "OPERADOR")
    void confirmarAnonimizacaoDeDocumentoJaLiberadoFalha() throws Exception {
        Anexo legado = new Anexo();
        legado.setId(8L);
        legado.setTipo(TipoAnexo.DOCUMENTO_CLINICO_AVALIADOR);
        legado.setNomeArquivo("laudo-legado.pdf");
        processo.addAnexo(legado);
        when(processoService.edicaoBloqueada(processo)).thenReturn(false);

        mvc.perform(post("/processos/1/documento-clinico/8/confirmar-anonimizacao")
                .param("confirmo", "true").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("erro", org.hamcrest.Matchers.containsString("nao esta pendente")));

        verify(anexoRepository, never()).save(any());
    }
}
