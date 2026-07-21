package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.*;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.ProcessoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProcessoServiceTest {

    @Mock
    ProcessoRepository processoRepository;
    @Mock
    MembroUrgenciaRenalRepository membroRepository;
    ProcessoService service;

    // Usa o ProcessoValidator real (funcoes puras): as regras de negocio vivem
    // nele, e o servico apenas delega.
    @BeforeEach
    void setUp() {
        service = new ProcessoService(processoRepository, membroRepository, new ProcessoValidator());
    }

    private Parecer parecer(ResultadoParecer r) {
        Parecer p = new Parecer(new MembroUrgenciaRenal("HCPA", "Medico", null));
        p.setResultado(r);
        return p;
    }

    /** Parecer de um avaliador marcado como coordenador CET-RS. */
    private Parecer parecerCoordenador(ResultadoParecer r) {
        MembroUrgenciaRenal coordenador = new MembroUrgenciaRenal("CET-RS", "Coordenador", null);
        coordenador.setCoordenador(true);
        Parecer p = new Parecer(coordenador);
        p.setResultado(r);
        return p;
    }

    private Processo comPareceres(ResultadoParecer... resultados) {
        Processo p = new Processo();
        long id = 1;
        for (ResultadoParecer r : resultados) {
            Parecer par = parecer(r);
            par.setId(id++);
            p.addParecer(par);
        }
        return p;
    }

    /** Vincula um anexo de RESPOSTA_AVALIADOR ao parecer informado. */
    private void anexarResposta(Processo p, Parecer parecer) {
        Anexo a = new Anexo();
        a.setTipo(TipoAnexo.RESPOSTA_AVALIADOR);
        a.setParecer(parecer);
        p.addAnexo(a);
    }

    /** Anexa a resposta de todos os pareceres ja recebidos (resultado != null). */
    private void anexarRespostasParaTodosRecebidos(Processo p) {
        p.getPareceres().stream()
            .filter(par -> par.getResultado() != null)
            .forEach(par -> anexarResposta(p, par));
    }

    @Test
    void defereComDoisFavoraveis() {
        Processo p = comPareceres(ResultadoParecer.FAVORAVEL, ResultadoParecer.FAVORAVEL, null);
        assertThat(service.contarFavoraveis(p)).isEqualTo(2);
        assertThat(service.sugerirDecisao(p)).contains(StatusProcesso.DEFERIDO);
    }

    @Test
    void indefereQuandoTodosResponderamSemMaioriaFavoravel() {
        Processo p = comPareceres(ResultadoParecer.FAVORAVEL,
            ResultadoParecer.NAO_FAVORAVEL, ResultadoParecer.NAO_FAVORAVEL);
        assertThat(service.sugerirDecisao(p)).contains(StatusProcesso.INDEFERIDO);
    }

    @Test
    void coordenadorFavoravelDefereSozinhoMesmoComOutrosPareceresPendentes() {
        Processo p = new Processo();
        Parecer parCoord = parecerCoordenador(ResultadoParecer.FAVORAVEL);
        parCoord.setId(1L);
        p.addParecer(parCoord);
        // os outros 2 avaliadores ainda nao responderam (resultado == null)
        Parecer par2 = parecer(null);
        par2.setId(2L);
        Parecer par3 = parecer(null);
        par3.setId(3L);
        p.addParecer(par2);
        p.addParecer(par3);

        assertThat(service.temVotoCoordenadorFavoravel(p)).isTrue();
        assertThat(service.favoraveisNecessariosParaDeferir(p)).isEqualTo(1);
        assertThat(service.sugerirDecisao(p)).contains(StatusProcesso.DEFERIDO);
    }

    @Test
    void decidirDeferidoComApenasUmFavoravelDoCoordenador() {
        Processo p = new Processo();
        Parecer parCoord = parecerCoordenador(ResultadoParecer.FAVORAVEL);
        parCoord.setId(10L);
        p.addParecer(parCoord);
        anexarResposta(p, parCoord);
        when(processoRepository.findById(30L)).thenReturn(java.util.Optional.of(p));
        when(processoRepository.save(p)).thenReturn(p);

        service.decidir(30L, StatusProcesso.DEFERIDO, null);

        assertThat(p.getStatus()).isEqualTo(StatusProcesso.DEFERIDO);
        assertThat(service.deferidoPeloCoordenador(p)).isTrue();
    }

    @Test
    void edicaoBloqueadaSomenteQuandoProcessoEncerrado() {
        Processo emAndamento = new Processo();
        emAndamento.setStatus(StatusProcesso.ENVIADO);
        assertThat(service.edicaoBloqueada(emAndamento)).isFalse();

        for (StatusProcesso encerrado : java.util.List.of(
                StatusProcesso.DEFERIDO, StatusProcesso.INDEFERIDO, StatusProcesso.CANCELADO)) {
            Processo p = new Processo();
            p.setStatus(encerrado);
            assertThat(service.edicaoBloqueada(p)).isTrue();
        }
    }

    @Test
    void atualizarDadosRejeitaProcessoEncerrado() {
        Processo p = new Processo();
        p.setStatus(StatusProcesso.DEFERIDO);
        when(processoRepository.findById(50L)).thenReturn(java.util.Optional.of(p));

        assertThatThrownBy(() -> service.atualizarDados(50L, new Processo()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("encerrado");
    }

    @Test
    void decidirRejeitaProcessoJaEncerrado() {
        Processo p = new Processo();
        p.setStatus(StatusProcesso.DEFERIDO);
        when(processoRepository.findById(51L)).thenReturn(java.util.Optional.of(p));

        assertThatThrownBy(() -> service.decidir(51L, StatusProcesso.INDEFERIDO, "motivo"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("encerrado");
    }

    @Test
    void indeferidoContinuaExigindoDoisDesfavoraveisMesmoComCoordenadorNoProcesso() {
        // O peso especial do coordenador vale so para Deferir; um unico voto
        // desfavoravel do coordenador NAO indefere sozinho - continua exigindo
        // a maioria simples normal (2 de 3 desfavoraveis).
        Processo p = new Processo();
        Parecer coordDesfavoravel = parecerCoordenador(ResultadoParecer.NAO_FAVORAVEL);
        coordDesfavoravel.setId(99L);
        p.addParecer(coordDesfavoravel);
        Parecer par2 = parecer(null);
        par2.setId(100L);
        Parecer par3 = parecer(null);
        par3.setId(101L);
        p.addParecer(par2);
        p.addParecer(par3);
        anexarRespostasParaTodosRecebidos(p);
        when(processoRepository.findById(31L)).thenReturn(java.util.Optional.of(p));

        assertThatThrownBy(() -> service.decidir(31L, StatusProcesso.INDEFERIDO, "motivo"))
            .isInstanceOf(IllegalStateException.class);
        assertThat(p.getStatus()).isNotEqualTo(StatusProcesso.INDEFERIDO);
    }

    @Test
    void semSugestaoQuandoFaltamRespostas() {
        Processo p = comPareceres(ResultadoParecer.FAVORAVEL, null, null);
        assertThat(service.sugerirDecisao(p)).isEmpty();
    }

    @Test
    void numeracaoManualEm2026EAutomaticaEm2027() {
        assertThat(service.isNumeracaoAutomatica(2026)).isFalse();
        assertThat(service.isNumeracaoAutomatica(2027)).isTrue();
    }

    @Test
    void proximoNumeroFormataSequencialDoAno() {
        when(processoRepository.findMaxSequencialByAno(2027)).thenReturn(4);
        assertThat(service.proximoNumero(2027)).isEqualTo("05/2027");
    }

    @Test
    void proximoNumeroComecaEmUmQuandoAnoVazio() {
        when(processoRepository.findMaxSequencialByAno(2027)).thenReturn(null);
        assertThat(service.proximoNumero(2027)).isEqualTo("01/2027");
    }

    @Test
    void contarRespondidosIgnoraPendentes() {
        Processo p = comPareceres(ResultadoParecer.FAVORAVEL, ResultadoParecer.SEM_RESPOSTA, null);
        assertThat(service.contarRespondidos(p)).isEqualTo(2);
    }

    @Test
    void processoNasceSolicitado() {
        assertThat(new Processo().getStatus()).isEqualTo(StatusProcesso.SOLICITADO);
    }

    @Test
    void registrarEnvioMudaParaEnviado() {
        Processo p = new Processo();
        when(processoRepository.findById(1L)).thenReturn(java.util.Optional.of(p));
        when(processoRepository.save(p)).thenReturn(p);
        service.registrarEnvio(1L);
        assertThat(p.getStatus()).isEqualTo(StatusProcesso.ENVIADO);
    }

    @Test
    void atualizarStatusVaiParaSolicitaInformacaoQuandoMedicoPedeInfo() {
        Processo p = comPareceres(ResultadoParecer.FAVORAVEL,
            ResultadoParecer.SOLICITA_INFORMACAO, null);
        when(processoRepository.findById(2L)).thenReturn(java.util.Optional.of(p));
        when(processoRepository.save(p)).thenReturn(p);
        service.atualizarStatusPorPareceres(2L);
        assertThat(p.getStatus()).isEqualTo(StatusProcesso.SOLICITA_INFORMACAO);
    }

    @Test
    void atualizarStatusNaoRebaixaProcessoJaDecidido() {
        Processo p = comPareceres(ResultadoParecer.SOLICITA_INFORMACAO);
        p.setStatus(StatusProcesso.DEFERIDO);
        when(processoRepository.findById(3L)).thenReturn(java.util.Optional.of(p));
        service.atualizarStatusPorPareceres(3L);
        assertThat(p.getStatus()).isEqualTo(StatusProcesso.DEFERIDO);
    }

    @Test
    void retomarAposInformacaoVoltaParaEnviadoEReabreParecer() {
        Processo p = comPareceres(ResultadoParecer.FAVORAVEL,
            ResultadoParecer.SOLICITA_INFORMACAO, null);
        p.setStatus(StatusProcesso.SOLICITA_INFORMACAO);
        when(processoRepository.findById(20L)).thenReturn(java.util.Optional.of(p));
        when(processoRepository.save(p)).thenReturn(p);
        service.retomarAposInformacao(20L);
        assertThat(p.getStatus()).isEqualTo(StatusProcesso.ENVIADO);
        // o parecer que pediu informacao foi reaberto (resultado limpo)
        assertThat(p.getPareceres().get(1).getResultado()).isNull();
    }

    /**
     * Ao reabrir o parecer que pediu informacao, o reset deve ser COMPLETO:
     * nenhum metadado do voto antigo pode sobreviver (nao-repudio), mas dataEnvio
     * (o processo foi enviado de fato) deve ser preservado.
     */
    @Test
    void retomarAposInformacaoZeraTodosOsMetadadosDoParecerReaberto() {
        Processo p = comPareceres(ResultadoParecer.SOLICITA_INFORMACAO);
        Parecer reaberto = p.getPareceres().get(0);
        java.time.LocalDate envio = java.time.LocalDate.of(2026, 6, 1);
        reaberto.setDataEnvio(envio);
        reaberto.setDataResposta(java.time.LocalDate.of(2026, 6, 10));
        reaberto.setDataHoraVoto(java.time.LocalDateTime.of(2026, 6, 10, 9, 30));
        reaberto.setVotadoPor("avaliador1");
        reaberto.setOrigem(OrigemParecer.AVALIADOR_SISTEMA);
        reaberto.setJustificativa("Faltam exames laboratoriais.");
        p.setStatus(StatusProcesso.SOLICITA_INFORMACAO);
        when(processoRepository.findById(22L)).thenReturn(java.util.Optional.of(p));
        when(processoRepository.save(p)).thenReturn(p);

        service.retomarAposInformacao(22L);

        assertThat(reaberto.getResultado()).isNull();
        assertThat(reaberto.getDataResposta()).isNull();
        assertThat(reaberto.getDataHoraVoto()).isNull();
        assertThat(reaberto.getVotadoPor()).isNull();
        assertThat(reaberto.getOrigem()).isNull();
        assertThat(reaberto.getJustificativa()).isNull();
        // dataEnvio NAO e tocada (o processo foi enviado de fato)
        assertThat(reaberto.getDataEnvio()).isEqualTo(envio);
    }

    /**
     * Pareceres ja definitivos (FAVORAVEL/NAO_FAVORAVEL) NAO podem ser tocados
     * na retomada — so os que estavam em SOLICITA_INFORMACAO sao reabertos.
     */
    @Test
    void retomarAposInformacaoNaoTocaPareceresDefinitivos() {
        Processo p = comPareceres(ResultadoParecer.FAVORAVEL,
            ResultadoParecer.SOLICITA_INFORMACAO, ResultadoParecer.NAO_FAVORAVEL);
        Parecer favoravel = p.getPareceres().get(0);
        favoravel.setVotadoPor("avaliador1");
        favoravel.setOrigem(OrigemParecer.AVALIADOR_SISTEMA);
        favoravel.setJustificativa("Caso elegivel.");
        favoravel.setDataResposta(java.time.LocalDate.of(2026, 6, 5));
        Parecer naoFavoravel = p.getPareceres().get(2);
        naoFavoravel.setVotadoPor("operador");
        p.setStatus(StatusProcesso.SOLICITA_INFORMACAO);
        when(processoRepository.findById(23L)).thenReturn(java.util.Optional.of(p));
        when(processoRepository.save(p)).thenReturn(p);

        service.retomarAposInformacao(23L);

        // o do meio foi reaberto
        assertThat(p.getPareceres().get(1).getResultado()).isNull();
        // os definitivos permanecem intactos
        assertThat(favoravel.getResultado()).isEqualTo(ResultadoParecer.FAVORAVEL);
        assertThat(favoravel.getVotadoPor()).isEqualTo("avaliador1");
        assertThat(favoravel.getOrigem()).isEqualTo(OrigemParecer.AVALIADOR_SISTEMA);
        assertThat(favoravel.getJustificativa()).isEqualTo("Caso elegivel.");
        assertThat(favoravel.getDataResposta()).isEqualTo(java.time.LocalDate.of(2026, 6, 5));
        assertThat(naoFavoravel.getResultado()).isEqualTo(ResultadoParecer.NAO_FAVORAVEL);
        assertThat(naoFavoravel.getVotadoPor()).isEqualTo("operador");
    }

    @Test
    void decidirBloqueiaQuandoAguardandoInformacaoComplementar() {
        Processo p = comPareceres(ResultadoParecer.NAO_FAVORAVEL,
            ResultadoParecer.NAO_FAVORAVEL, ResultadoParecer.SOLICITA_INFORMACAO);
        anexarRespostasParaTodosRecebidos(p);
        p.setStatus(StatusProcesso.SOLICITA_INFORMACAO);
        when(processoRepository.findById(21L)).thenReturn(java.util.Optional.of(p));
        assertThatThrownBy(() -> service.decidir(21L, StatusProcesso.INDEFERIDO, "motivo"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("informacao complementar");
        assertThat(p.getStatus()).isEqualTo(StatusProcesso.SOLICITA_INFORMACAO);
    }

    @Test
    void decidirDeferidoExigeNoMinimoDoisFavoraveis() {
        Processo p = comPareceres(ResultadoParecer.FAVORAVEL,
            ResultadoParecer.NAO_FAVORAVEL, ResultadoParecer.NAO_FAVORAVEL);
        when(processoRepository.findById(4L)).thenReturn(java.util.Optional.of(p));
        assertThatThrownBy(() -> service.decidir(4L, StatusProcesso.DEFERIDO, null))
            .isInstanceOf(IllegalStateException.class);
        assertThat(p.getStatus()).isNotEqualTo(StatusProcesso.DEFERIDO);
    }

    @Test
    void decidirDeferidoComDoisFavoraveis() {
        Processo p = comPareceres(ResultadoParecer.FAVORAVEL,
            ResultadoParecer.FAVORAVEL, ResultadoParecer.NAO_FAVORAVEL);
        anexarRespostasParaTodosRecebidos(p);
        when(processoRepository.findById(5L)).thenReturn(java.util.Optional.of(p));
        when(processoRepository.save(p)).thenReturn(p);
        service.decidir(5L, StatusProcesso.DEFERIDO, null);
        assertThat(p.getStatus()).isEqualTo(StatusProcesso.DEFERIDO);
    }

    @Test
    void decidirIndeferidoExigeNoMinimoDoisDesfavoraveis() {
        Processo p = comPareceres(ResultadoParecer.FAVORAVEL,
            ResultadoParecer.FAVORAVEL, ResultadoParecer.NAO_FAVORAVEL);
        when(processoRepository.findById(6L)).thenReturn(java.util.Optional.of(p));
        assertThatThrownBy(() -> service.decidir(6L, StatusProcesso.INDEFERIDO, "motivo"))
            .isInstanceOf(IllegalStateException.class);
        assertThat(p.getStatus()).isNotEqualTo(StatusProcesso.INDEFERIDO);
    }

    @Test
    void decidirIndeferidoComDoisDesfavoraveis() {
        Processo p = comPareceres(ResultadoParecer.NAO_FAVORAVEL,
            ResultadoParecer.NAO_FAVORAVEL, ResultadoParecer.FAVORAVEL);
        anexarRespostasParaTodosRecebidos(p);
        when(processoRepository.findById(7L)).thenReturn(java.util.Optional.of(p));
        when(processoRepository.save(p)).thenReturn(p);
        service.decidir(7L, StatusProcesso.INDEFERIDO, "motivo");
        assertThat(p.getStatus()).isEqualTo(StatusProcesso.INDEFERIDO);
    }

    @Test
    void decidirIndeferidoExigeMotivo() {
        // Defesa em profundidade: mesmo chamando o service diretamente (sem
        // passar pela pre-validacao do controller), Indeferido sem motivo
        // deve ser rejeitado.
        Processo p = comPareceres(ResultadoParecer.NAO_FAVORAVEL,
            ResultadoParecer.NAO_FAVORAVEL, ResultadoParecer.FAVORAVEL);
        anexarRespostasParaTodosRecebidos(p);
        when(processoRepository.findById(24L)).thenReturn(java.util.Optional.of(p));
        assertThatThrownBy(() -> service.decidir(24L, StatusProcesso.INDEFERIDO, "  "))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("motivo");
        assertThat(p.getStatus()).isNotEqualTo(StatusProcesso.INDEFERIDO);
    }

    @Test
    void decidirBloqueiaQuandoRespostaRecebidaSemAnexo() {
        // 2 favoraveis recebidos, mas sem o anexo da resposta -> nao pode deferir
        Processo p = comPareceres(ResultadoParecer.FAVORAVEL,
            ResultadoParecer.FAVORAVEL, ResultadoParecer.NAO_FAVORAVEL);
        when(processoRepository.findById(8L)).thenReturn(java.util.Optional.of(p));
        assertThatThrownBy(() -> service.decidir(8L, StatusProcesso.DEFERIDO, null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Anexe a resposta");
        assertThat(p.getStatus()).isNotEqualTo(StatusProcesso.DEFERIDO);
    }

    @Test
    void pareceresRecebidosSemAnexoListaApenasOsFaltantes() {
        Processo p = comPareceres(ResultadoParecer.FAVORAVEL,
            ResultadoParecer.FAVORAVEL, null);
        // anexa a resposta apenas do primeiro parecer recebido
        Parecer primeiro = p.getPareceres().get(0);
        anexarResposta(p, primeiro);
        var faltantes = service.pareceresRecebidosSemAnexo(p);
        assertThat(faltantes).containsExactly(p.getPareceres().get(1));
    }

    @Test
    void cadastrarExigeExatamenteTresMedicos() {
        Processo p = new Processo();
        assertThatThrownBy(() -> service.cadastrar(p, java.util.List.of(1L, 2L)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("exatamente");
        assertThatThrownBy(() -> service.cadastrar(p, java.util.List.of(1L, 2L, 3L, 4L)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.cadastrar(p, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Parecer votado diretamente pelo avaliador autenticado (AVALIADOR_SISTEMA)
     * NAO deve aparecer em pareceresRecebidosSemAnexo — a prova e o registro
     * autenticado, nao um anexo.
     */
    @Test
    void pareceresRecebidosSemAnexoIgnoraOrigemAvaliadorSistema() {
        Processo p = comPareceres(ResultadoParecer.FAVORAVEL,
            ResultadoParecer.FAVORAVEL, ResultadoParecer.NAO_FAVORAVEL);
        // Primeiro: operador lancou (origem null = OPERADOR_EMAIL) — sem anexo, deve aparecer
        // Segundo: avaliador autenticado (AVALIADOR_SISTEMA) — sem anexo, mas NAO deve aparecer
        // Terceiro: operador lancou, mas com anexo — nao deve aparecer
        Parecer segundo = p.getPareceres().get(1);
        segundo.setOrigem(OrigemParecer.AVALIADOR_SISTEMA);
        Parecer terceiro = p.getPareceres().get(2);
        anexarResposta(p, terceiro);

        var faltantes = service.pareceresRecebidosSemAnexo(p);
        // Apenas o primeiro (origem null, sem anexo) deve constar
        assertThat(faltantes).containsExactly(p.getPareceres().get(0));
    }

    /**
     * Com todos os pareceres de origem AVALIADOR_SISTEMA (sem nenhum anexo),
     * pareceresRecebidosSemAnexo deve retornar vazio — pode decidir sem anexo.
     */
    @Test
    void decidirPermitidoQuandoTodosVotosForamPeloPortal() {
        Processo p = comPareceres(ResultadoParecer.FAVORAVEL,
            ResultadoParecer.FAVORAVEL, ResultadoParecer.NAO_FAVORAVEL);
        // Marca todos como voto direto do portal
        p.getPareceres().forEach(par -> par.setOrigem(OrigemParecer.AVALIADOR_SISTEMA));

        when(processoRepository.findById(99L)).thenReturn(java.util.Optional.of(p));
        when(processoRepository.save(p)).thenReturn(p);

        // NAO deve lancar excecao de "Anexe a resposta"
        service.decidir(99L, StatusProcesso.DEFERIDO, null);
        assertThat(p.getStatus()).isEqualTo(StatusProcesso.DEFERIDO);
    }

    @Test
    void reabrirVoltaParaEnviadoELimpaDecisao() {
        Processo p = comPareceres(ResultadoParecer.FAVORAVEL,
            ResultadoParecer.FAVORAVEL, ResultadoParecer.NAO_FAVORAVEL);
        anexarRespostasParaTodosRecebidos(p);
        when(processoRepository.findById(30L)).thenReturn(java.util.Optional.of(p));
        when(processoRepository.save(p)).thenReturn(p);
        service.decidir(30L, StatusProcesso.DEFERIDO, null);
        assertThat(p.getStatus()).isEqualTo(StatusProcesso.DEFERIDO);

        service.reabrir(30L);

        assertThat(p.getStatus()).isEqualTo(StatusProcesso.ENVIADO);
        assertThat(p.getDataDecisao()).isNull();
        assertThat(p.getMotivoIndeferimento()).isNull();
    }

    /**
     * Defesa contra mass assignment: o form faz bind da entidade Processo
     * inteira (@ModelAttribute), entao um request malicioso poderia setar
     * status=DEFERIDO (ou outro campo pos-decisao) antes de chamar cadastrar().
     * cadastrar() precisa forcar o estado inicial correto, ignorando o que
     * veio no objeto de entrada.
     */
    @Test
    void cadastrarForcaStatusSolicitadoIgnorandoValorMalicioso() {
        Processo p = new Processo();
        p.setDataSituacaoEspecial(java.time.LocalDate.of(2026, 1, 10));
        // Simula um request malicioso que tenta pular o fluxo de decisao.
        p.setStatus(StatusProcesso.DEFERIDO);
        p.setDataDecisao(java.time.LocalDateTime.of(2026, 1, 1, 0, 0));
        p.setMotivoIndeferimento("forjado");
        p.setEmailEnviadoSolicitante(true);

        MembroUrgenciaRenal medico = new MembroUrgenciaRenal("HCPA", "Medico", null);
        medico.setId(1L);
        when(membroRepository.findById(1L)).thenReturn(java.util.Optional.of(medico));
        when(membroRepository.findById(2L)).thenReturn(java.util.Optional.of(medico));
        when(membroRepository.findById(3L)).thenReturn(java.util.Optional.of(medico));
        when(processoRepository.save(p)).thenReturn(p);

        Processo salvo = service.cadastrar(p, java.util.List.of(1L, 2L, 3L));

        assertThat(salvo.getStatus()).isEqualTo(StatusProcesso.SOLICITADO);
        assertThat(salvo.getDataDecisao()).isNull();
        assertThat(salvo.getMotivoIndeferimento()).isNull();
        assertThat(salvo.isEmailEnviadoSolicitante()).isFalse();
    }

    /**
     * O coordenador CET-RS defere sozinho e imediatamente ao votar Favoravel,
     * mesmo que o processo esteja pausado (SOLICITA_INFORMACAO) por causa do
     * parecer de outro avaliador comum. A pausa nao se aplica a essa regra.
     */
    @Test
    void coordenadorFavoravelDefereMesmoComProcessoPausadoPorSolicitaInformacao() {
        Processo p = new Processo();
        p.setStatus(StatusProcesso.SOLICITA_INFORMACAO);

        Parecer parInfo = parecer(ResultadoParecer.SOLICITA_INFORMACAO);
        parInfo.setId(1L);
        p.addParecer(parInfo);
        anexarResposta(p, parInfo);

        Parecer parCoord = parecerCoordenador(ResultadoParecer.FAVORAVEL);
        parCoord.setId(2L);
        p.addParecer(parCoord);
        anexarResposta(p, parCoord);

        Parecer par3 = parecer(null);
        par3.setId(3L);
        p.addParecer(par3);

        when(processoRepository.findById(40L)).thenReturn(java.util.Optional.of(p));
        when(processoRepository.save(p)).thenReturn(p);

        Processo resultado = service.tentarDecisaoAutomatica(40L);

        assertThat(resultado.getStatus()).isEqualTo(StatusProcesso.DEFERIDO);
        assertThat(service.deferidoPeloCoordenador(resultado)).isTrue();
    }

    /**
     * Mesma regra pelo caminho manual (decidir): validarPausaDecisao nao pode
     * bloquear quando o coordenador votou Favoravel.
     */
    @Test
    void decidirManualPermiteDeferirComCoordenadorMesmoPausado() {
        Processo p = new Processo();
        p.setStatus(StatusProcesso.SOLICITA_INFORMACAO);

        Parecer parInfo = parecer(ResultadoParecer.SOLICITA_INFORMACAO);
        parInfo.setId(1L);
        p.addParecer(parInfo);
        anexarResposta(p, parInfo);

        Parecer parCoord = parecerCoordenador(ResultadoParecer.FAVORAVEL);
        parCoord.setId(2L);
        p.addParecer(parCoord);
        anexarResposta(p, parCoord);

        when(processoRepository.findById(41L)).thenReturn(java.util.Optional.of(p));
        when(processoRepository.save(p)).thenReturn(p);

        service.decidir(41L, StatusProcesso.DEFERIDO, null);

        assertThat(p.getStatus()).isEqualTo(StatusProcesso.DEFERIDO);
    }

    @Test
    void reabrirLancaErroSeProcessoNaoEstiverFinalizado() {
        Processo p = comPareceres(ResultadoParecer.FAVORAVEL, null, null);
        p.setStatus(StatusProcesso.ENVIADO);
        when(processoRepository.findById(31L)).thenReturn(java.util.Optional.of(p));

        assertThatThrownBy(() -> service.reabrir(31L))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("encerrados");
        assertThat(p.getStatus()).isEqualTo(StatusProcesso.ENVIADO);
    }
}
