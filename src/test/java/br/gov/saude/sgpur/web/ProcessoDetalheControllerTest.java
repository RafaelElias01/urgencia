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
import org.springframework.mock.web.MockMultipartFile;
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
 * Testes do ProcessoDetalheController: criacao (numeracao automatica vs
 * manual, validacoes), a tela de detalhe (gating das abas 1-5 e o
 * sub-rotulo de status), edicao, reabertura, exclusao e o recebimento
 * (passo 1: solicitacao original + capa automatica).
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
    @MockitoBean private RelatorioService relatorioService;
    @MockitoBean private br.gov.saude.sgpur.service.SolicitacaoOnlineService solicitacaoOnlineService;
    @MockitoBean private br.gov.saude.sgpur.repository.SolicitacaoOnlineRepository solicitacaoOnlineRepository;
    // GlobalModelAdvice (@ControllerAdvice global) precisa dessas duas pro
    // contexto do @WebMvcTest subir - ver ArquivoControllerTest.
    @MockitoBean private UsuarioRepository usuarioRepository;
    @MockitoBean private ParecerRepository parecerRepository;

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
        // Por padrao nenhum processo veio do Portal do Solicitante (fluxo
        // tradicional); testes especificos sobrescrevem quando precisarem.
        when(fluxoService.veioDoPortal(any())).thenReturn(false);
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
    void novoComNumeracaoAutomaticaNaoSugereNumero() throws Exception {
        int ano = Year.now().getValue();
        when(processoService.isNumeracaoAutomatica(ano)).thenReturn(true);

        mvc.perform(get("/processos/novo"))
            .andExpect(status().isOk())
            .andExpect(view().name("processos/form"))
            .andExpect(model().attribute("numeracaoAutomatica", true));

        verify(processoService, never()).proximoNumero(anyInt());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void novoComNumeracaoManualSugereOProximoNumero() throws Exception {
        int ano = Year.now().getValue();
        when(processoService.isNumeracaoAutomatica(ano)).thenReturn(false);
        when(processoService.proximoNumero(ano)).thenReturn("05/" + ano);

        mvc.perform(get("/processos/novo"))
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
            .with(csrf());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void salvarSemNumeroEmNumeracaoManualEhRejeitado() throws Exception {
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
        when(processoService.isNumeracaoAutomatica(2026)).thenReturn(true);
        when(membroService.listarAtivos()).thenReturn(List.of());

        mvc.perform(post("/processos")
                .param("pacienteNome", "Maria Silva")
                .param("pacienteRgct", "RGCT123")
                .param("solicitanteEquipe", "Equipe A")
                .param("solicitanteEmail", "equipe@ex.com")
                .param("dataSituacaoEspecial", "2026-07-01")
                .param("medicoIds", "1", "2")
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(view().name("processos/form"));

        verify(processoService, never()).cadastrar(any(), any());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void salvarComCamposObrigatoriosEmBrancoEhRejeitadoPelaBeanValidation() throws Exception {
        when(processoService.isNumeracaoAutomatica(2026)).thenReturn(true);
        when(membroService.listarAtivos()).thenReturn(List.of());

        mvc.perform(post("/processos")
                .param("dataSituacaoEspecial", "2026-07-01")
                .param("medicoIds", "1", "2", "3")
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(view().name("processos/form"));

        verify(processoService, never()).cadastrar(any(), any());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void salvarComDadosValidosCadastraERedireciona() throws Exception {
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
        Anexo solicitacao = new Anexo();
        solicitacao.setTipo(TipoAnexo.SOLICITACAO_RECEBIDA);
        Anexo capa = new Anexo();
        capa.setTipo(TipoAnexo.CAPA_PROCESSO);
        processo.addAnexo(solicitacao);
        processo.addAnexo(capa);
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
        // liberadoRespostas exige recebimentoFeito (solicitacao + capa) E
        // envioFeito (1o parecer com dataEnvio) - sem isso liberadoDecisao fica
        // false mesmo com maioria formada.
        Anexo solicitacao = new Anexo();
        solicitacao.setTipo(TipoAnexo.SOLICITACAO_RECEBIDA);
        Anexo capa = new Anexo();
        capa.setTipo(TipoAnexo.CAPA_PROCESSO);
        processo.addAnexo(solicitacao);
        processo.addAnexo(capa);
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
    void detalheExpoeProcessoVeioDoPortalFalseNoFluxoTradicional() throws Exception {
        mvc.perform(get("/processos/1"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("processoVeioDoPortal", false))
            .andExpect(model().attribute("solicitacaoOnlineOrigemId", (Object) null));

        verify(solicitacaoOnlineRepository, never()).findIdByProcessoGeradoId(anyLong());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void detalheExpoeProcessoVeioDoPortalTrueELinkDaOrigem() throws Exception {
        when(fluxoService.veioDoPortal(processo)).thenReturn(true);
        when(solicitacaoOnlineRepository.findIdByProcessoGeradoId(1L)).thenReturn(Optional.of(42L));
        // Passo 1 (Recebimento) so exige a capa quando veio do portal.
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

    // ----- recebimento -----

    @Test
    @WithMockUser(roles = "OPERADOR")
    void recebimentoBloqueadoQuandoProcessoEncerrado() throws Exception {
        when(processoService.edicaoBloqueada(processo)).thenReturn(true);

        mvc.perform(multipart("/processos/1/recebimento").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/processos/1#recebimento"))
            .andExpect(flash().attribute("erro", ProcessoValidator.MSG_ENCERRADO));

        verify(anexoStorage, never()).salvar(any(), any(), any(), any());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void recebimentoSemArquivoApenasGeraACapa() throws Exception {
        when(processoService.edicaoBloqueada(processo)).thenReturn(false);
        when(relatorioService.gerarCapaProcesso(processo)).thenReturn("capa-fake".getBytes());
        when(anexoStorage.salvarBytes(any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            Anexo a = new Anexo();
            a.setId(77L);
            return a;
        });

        mvc.perform(multipart("/processos/1/recebimento").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/processos/1#recebimento"))
            .andExpect(flash().attribute("msg", org.hamcrest.Matchers.containsString("capa gerada")));

        verify(anexoStorage, never()).salvar(any(), any(), any(), any());
        verify(anexoStorage).salvarBytes(eq(processo), eq(TipoAnexo.CAPA_PROCESSO), anyString(),
            eq("capa-processo-01-2026.pdf"), eq("application/pdf"), eq("capa-fake".getBytes()));
        verify(anexoStorage).removerAntigosDoTipo(1L, TipoAnexo.CAPA_PROCESSO, 77L);
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void recebimentoComArquivoAnexaESalvaACapa() throws Exception {
        when(processoService.edicaoBloqueada(processo)).thenReturn(false);
        when(relatorioService.gerarCapaProcesso(processo)).thenReturn("capa-fake".getBytes());
        MockMultipartFile arquivo = new MockMultipartFile("arquivo", "solicitacao.pdf",
            "application/pdf", "conteudo".getBytes());
        when(anexoStorage.salvar(any(), any(), any(), any())).thenAnswer(inv -> {
            Anexo a = new Anexo();
            a.setId(88L);
            return a;
        });
        when(anexoStorage.salvarBytes(any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            Anexo a = new Anexo();
            a.setId(77L);
            return a;
        });

        mvc.perform(multipart("/processos/1/recebimento").file(arquivo).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("msg", org.hamcrest.Matchers.containsString("solicitacao original anexada")));

        verify(anexoStorage).salvar(eq(processo), eq(TipoAnexo.SOLICITACAO_RECEBIDA), anyString(), eq(arquivo));
        verify(anexoStorage).removerAntigosDoTipo(1L, TipoAnexo.SOLICITACAO_RECEBIDA, 88L);
        verify(auditoria, times(2)).registrar(eq("ANEXO_ADICIONADO"), anyString());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void recebimentoComFalhaAoAnexarNaoGeraACapa() throws Exception {
        when(processoService.edicaoBloqueada(processo)).thenReturn(false);
        MockMultipartFile arquivo = new MockMultipartFile("arquivo", "solicitacao.pdf",
            "application/pdf", "conteudo".getBytes());
        when(anexoStorage.salvar(any(), any(), any(), any())).thenThrow(new java.io.IOException("disco cheio"));

        mvc.perform(multipart("/processos/1/recebimento").file(arquivo).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("erro", org.hamcrest.Matchers.containsString("disco cheio")));

        verify(relatorioService, never()).gerarCapaProcesso(any());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void recebimentoComFalhaAoGerarCapaAvisaSemQuebrar() throws Exception {
        when(processoService.edicaoBloqueada(processo)).thenReturn(false);
        when(relatorioService.gerarCapaProcesso(processo)).thenThrow(new RuntimeException("template quebrado"));

        mvc.perform(multipart("/processos/1/recebimento").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("erro", org.hamcrest.Matchers.containsString("template quebrado")));
    }
}
