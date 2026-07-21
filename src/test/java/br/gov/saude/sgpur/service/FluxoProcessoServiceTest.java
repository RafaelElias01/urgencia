package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.*;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.ProcessoRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class FluxoProcessoServiceTest {

    @Mock
    ProcessoRepository processoRepository;
    @Mock
    MembroUrgenciaRenalRepository membroRepository;

    private FluxoProcessoService fluxo() {
        ProcessoService ps = new ProcessoService(processoRepository, membroRepository, new ProcessoValidator());
        return new FluxoProcessoService(ps);
    }

    private Processo processoComTresPareceres() {
        Processo p = new Processo();
        for (int i = 0; i < 3; i++) {
            p.addParecer(new Parecer(new MembroUrgenciaRenal("INST" + i, "Medico " + i, null)));
        }
        return p;
    }

    /** Etapa 1 (Recebimento) concluida: solicitacao original + capa do processo. */
    private void anexarRecebimentoCompleto(Processo p) {
        Anexo original = new Anexo();
        original.setTipo(TipoAnexo.SOLICITACAO_RECEBIDA);
        p.addAnexo(original);
        Anexo capa = new Anexo();
        capa.setTipo(TipoAnexo.CAPA_PROCESSO);
        p.addAnexo(capa);
    }

    /** Etapa 2 (Envio) concluida: doc clinico PDF anexado + dataEnvio nos 3 pareceres. */
    private void registrarEnvioCompleto(Processo p) {
        Anexo doc = new Anexo();
        doc.setTipo(TipoAnexo.DOCUMENTO_CLINICO_AVALIADOR);
        doc.setContentType("application/pdf");
        p.addAnexo(doc);
        p.getPareceres().forEach(par -> par.setDataEnvio(LocalDate.now()));
    }

    /**
     * Etapa 3 (Respostas) concluida por maioria: 2 pareceres com o resultado
     * informado e anexo de resposta vinculado; o 3o fica sem responder.
     */
    private void registrarMaioria(Processo p, ResultadoParecer resultado) {
        long id = 1;
        for (Parecer par : p.getPareceres()) {
            if (par.getId() == null) par.setId(id++);
        }
        for (int i = 0; i < 2; i++) {
            Parecer par = p.getPareceres().get(i);
            par.setResultado(resultado);
            Anexo resp = new Anexo();
            resp.setTipo(TipoAnexo.RESPOSTA_AVALIADOR);
            resp.setParecer(par);
            p.addAnexo(resp);
        }
    }

    /** Monta um processo com as etapas 1-3 completas, pronto para a Decisao (favoravel). */
    private Processo processoProntoParaDecisao() {
        Processo p = processoComTresPareceres();
        p.setStatus(StatusProcesso.ENVIADO);
        anexarRecebimentoCompleto(p);
        registrarEnvioCompleto(p);
        registrarMaioria(p, ResultadoParecer.FAVORAVEL);
        return p;
    }

    @Test
    void recebimentoEhAtualQuandoNadaFeito() {
        List<EtapaFluxo> etapas = fluxo().montarEtapas(processoComTresPareceres());
        assertThat(etapas).isNotEmpty();
        assertThat(etapas.get(0).titulo()).contains("Recebimento");
        assertThat(etapas.get(0).estado()).isEqualTo(EtapaFluxo.Estado.ATUAL);
    }

    @Test
    void envioConcluiQuandoTodosPareceresTemDataEnvio() {
        Processo p = processoComTresPareceres();
        anexarRecebimentoCompleto(p);
        p.getPareceres().forEach(par -> par.setDataEnvio(LocalDate.now()));
        EtapaFluxo envio = fluxo().montarEtapas(p).stream()
            .filter(e -> e.titulo().startsWith("Envio")).findFirst().orElseThrow();
        assertThat(envio.estado()).isEqualTo(EtapaFluxo.Estado.CONCLUIDA);
    }

    @Test
    void incluiEtapaDeOficioApenasQuandoIndeferido() {
        Processo p = processoComTresPareceres();
        p.setStatus(StatusProcesso.INDEFERIDO);
        boolean temOficio = fluxo().montarEtapas(p).stream()
            .anyMatch(e -> e.titulo().toLowerCase().contains("oficio"));
        assertThat(temOficio).isTrue();

        Processo p2 = processoComTresPareceres();
        p2.setStatus(StatusProcesso.DEFERIDO);
        boolean temOficio2 = fluxo().montarEtapas(p2).stream()
            .anyMatch(e -> e.titulo().toLowerCase().contains("oficio"));
        assertThat(temOficio2).isFalse();
    }

    @Test
    void resumoPendenciaApontaEtapaAtual() {
        String resumo = fluxo().resumoPendencia(processoComTresPareceres());
        assertThat(resumo).contains("Recebimento");
    }

    @Test
    void incluiEtapaInformacaoComplementarQuandoSolicitaInformacao() {
        Processo p = processoComTresPareceres();
        p.setStatus(StatusProcesso.SOLICITA_INFORMACAO);
        List<EtapaFluxo> etapas = fluxo().montarEtapas(p);

        EtapaFluxo info = etapas.stream()
            .filter(e -> e.titulo().equals("Informacao complementar")).findFirst().orElseThrow();
        assertThat(info.estado()).isNotEqualTo(EtapaFluxo.Estado.CONCLUIDA);

        // a decisao fica bloqueada (nunca CONCLUIDA, e nem ATUAL, pois a info pausa o fluxo)
        EtapaFluxo decisao = etapas.stream()
            .filter(e -> e.titulo().equals("Decisao final")).findFirst().orElseThrow();
        assertThat(decisao.estado()).isEqualTo(EtapaFluxo.Estado.PENDENTE);
    }

    @Test
    void naoIncluiEtapaInformacaoComplementarFora() {
        Processo p = processoComTresPareceres();
        p.setStatus(StatusProcesso.ENVIADO);
        boolean tem = fluxo().montarEtapas(p).stream()
            .anyMatch(e -> e.titulo().equals("Informacao complementar"));
        assertThat(tem).isFalse();
    }

    @Test
    void respostasPodeConcluirComMaioriaSemAguardarTerceiroParecer() {
        // 2 favoraveis (com anexo de resposta) + 1 ainda sem responder.
        // Por maioria simples a etapa Respostas ja deve estar CONCLUIDA, sem
        // ficar "Aguardando parecer (2/3)".
        Processo p = processoProntoParaDecisao();
        // terceiro parecer continua sem resposta

        EtapaFluxo respostas = fluxo().montarEtapas(p).stream()
            .filter(e -> e.titulo().equals("Respostas dos medicos")).findFirst().orElseThrow();
        assertThat(respostas.estado()).isEqualTo(EtapaFluxo.Estado.CONCLUIDA);
        assertThat(respostas.detalhe()).doesNotContain("Faltam");
        assertThat(respostas.detalhe()).contains("Maioria formada");

        // e a Decisao final fica como etapa ATUAL (liberada), nao PENDENTE.
        EtapaFluxo decisao = fluxo().montarEtapas(p).stream()
            .filter(e -> e.titulo().equals("Decisao final")).findFirst().orElseThrow();
        assertThat(decisao.estado()).isEqualTo(EtapaFluxo.Estado.ATUAL);
    }

    @Test
    void incluiEtapaComprovanteSntApenasQuandoDeferido() {
        Processo def = processoComTresPareceres();
        def.setStatus(StatusProcesso.DEFERIDO);
        boolean temSnt = fluxo().montarEtapas(def).stream()
            .anyMatch(e -> e.titulo().equals("Comprovante SNT"));
        assertThat(temSnt).isTrue();

        Processo ind = processoComTresPareceres();
        ind.setStatus(StatusProcesso.INDEFERIDO);
        boolean temSnt2 = fluxo().montarEtapas(ind).stream()
            .anyMatch(e -> e.titulo().equals("Comprovante SNT"));
        assertThat(temSnt2).isFalse();
    }

    @Test
    void deferidoSoConcluiComprovanteSntComAnexo() {
        Processo p = processoProntoParaDecisao();
        p.setStatus(StatusProcesso.DEFERIDO);

        EtapaFluxo sntSem = fluxo().montarEtapas(p).stream()
            .filter(e -> e.titulo().equals("Comprovante SNT")).findFirst().orElseThrow();
        assertThat(sntSem.estado()).isNotEqualTo(EtapaFluxo.Estado.CONCLUIDA);

        Anexo comprovante = new Anexo();
        comprovante.setTipo(TipoAnexo.COMPROVANTE_SNT);
        p.addAnexo(comprovante);

        EtapaFluxo sntCom = fluxo().montarEtapas(p).stream()
            .filter(e -> e.titulo().equals("Comprovante SNT")).findFirst().orElseThrow();
        assertThat(sntCom.estado()).isEqualTo(EtapaFluxo.Estado.CONCLUIDA);
    }

    @Test
    void etapaPosteriorNaoFicaVerdeSeEtapaAnteriorAindaEstaPendente() {
        // Bug real observado em producao: processo Deferido ja tinha a resposta
        // ao solicitante marcada (e-mail + comprovante de envio anexado) ANTES
        // de anexar o comprovante SNT (etapa anterior na timeline). Cada etapa
        // calculava sua propria conclusao isoladamente, entao "Resposta ao
        // solicitante" aparecia CONCLUIDA (verde) mesmo com "Comprovante SNT"
        // (etapa 5, antes dela) ainda pendente - timeline "fora de ordem".
        Processo p = processoComTresPareceres();
        p.setStatus(StatusProcesso.DEFERIDO);
        // NAO anexa COMPROVANTE_SNT: etapa "Comprovante SNT" fica pendente/atual.
        p.setEmailEnviadoSolicitante(true);
        Anexo comprovanteEnvio = new Anexo();
        comprovanteEnvio.setTipo(TipoAnexo.COMPROVANTE_ENVIO_SOLICITANTE);
        p.addAnexo(comprovanteEnvio);

        List<EtapaFluxo> etapas = fluxo().montarEtapas(p);

        EtapaFluxo snt = etapas.stream()
            .filter(e -> e.titulo().equals("Comprovante SNT")).findFirst().orElseThrow();
        assertThat(snt.estado()).isNotEqualTo(EtapaFluxo.Estado.CONCLUIDA);

        EtapaFluxo resposta = etapas.stream()
            .filter(e -> e.titulo().equals("Resposta ao solicitante")).findFirst().orElseThrow();
        assertThat(resposta.estado()).isNotEqualTo(EtapaFluxo.Estado.CONCLUIDA);
        assertThat(resposta.estado()).isEqualTo(EtapaFluxo.Estado.PENDENTE);
    }

    // ----------------------------------------------------------------
    // Bug irmao encontrado durante a auditoria: a etapa "Recebimento da
    // solicitacao" so checava SOLICITACAO_RECEBIDA, mas o gate real de
    // navegacao (ProcessoDetalheController.recebimentoFeito) e a propria
    // regra de negocio (CLAUDE.md) exigem TAMBEM a CAPA_PROCESSO. Sem o
    // segundo anexo, a etapa 1 podia aparecer CONCLUIDA (verde) e liberar
    // toda a timeline (incluindo Envio) mesmo faltando a capa.
    // ----------------------------------------------------------------

    @Test
    void recebimentoNaoConcluiSoComSolicitacaoOriginalSemCapa() {
        Processo p = processoComTresPareceres();
        Anexo original = new Anexo();
        original.setTipo(TipoAnexo.SOLICITACAO_RECEBIDA);
        p.addAnexo(original);
        // falta CAPA_PROCESSO

        EtapaFluxo recebimento = fluxo().montarEtapas(p).get(0);
        assertThat(recebimento.estado()).isNotEqualTo(EtapaFluxo.Estado.CONCLUIDA);
        assertThat(recebimento.detalhe()).contains("capa do processo");

        // e por consequencia a etapa seguinte (Envio) nao pode ficar liberada/concluida
        EtapaFluxo envio = fluxo().montarEtapas(p).stream()
            .filter(e -> e.titulo().startsWith("Envio")).findFirst().orElseThrow();
        assertThat(envio.estado()).isEqualTo(EtapaFluxo.Estado.PENDENTE);
    }

    @Test
    void recebimentoNaoConcluiSoComCapaSemSolicitacaoOriginal() {
        Processo p = processoComTresPareceres();
        Anexo capa = new Anexo();
        capa.setTipo(TipoAnexo.CAPA_PROCESSO);
        p.addAnexo(capa);
        // falta SOLICITACAO_RECEBIDA

        EtapaFluxo recebimento = fluxo().montarEtapas(p).get(0);
        assertThat(recebimento.estado()).isNotEqualTo(EtapaFluxo.Estado.CONCLUIDA);
        assertThat(recebimento.detalhe()).contains("solicitacao original");
    }

    @Test
    void recebimentoConcluiComSolicitacaoOriginalECapa() {
        Processo p = processoComTresPareceres();
        anexarRecebimentoCompleto(p);

        EtapaFluxo recebimento = fluxo().montarEtapas(p).get(0);
        assertThat(recebimento.estado()).isEqualTo(EtapaFluxo.Estado.CONCLUIDA);
    }

    // ----------------------------------------------------------------
    // Fluxo feliz completo: cada etapa avanca PENDENTE -> ATUAL -> CONCLUIDA
    // em ordem, uma de cada vez, para o caso Deferido.
    // ----------------------------------------------------------------

    @Test
    void fluxoFelizCompletoDeferido() {
        Processo p = processoComTresPareceres();
        p.setStatus(StatusProcesso.SOLICITADO);

        // nada feito: so a etapa 1 esta ATUAL, o resto PENDENTE
        List<EtapaFluxo> e0 = fluxo().montarEtapas(p);
        assertThat(estado(e0, "Recebimento da solicitacao")).isEqualTo(EtapaFluxo.Estado.ATUAL);
        assertThat(estado(e0, "Envio aos 3 medicos")).isEqualTo(EtapaFluxo.Estado.PENDENTE);
        assertThat(estado(e0, "Respostas dos medicos")).isEqualTo(EtapaFluxo.Estado.PENDENTE);
        assertThat(estado(e0, "Decisao final")).isEqualTo(EtapaFluxo.Estado.PENDENTE);

        // etapa 1 completa -> etapa 2 fica ATUAL
        anexarRecebimentoCompleto(p);
        List<EtapaFluxo> e1 = fluxo().montarEtapas(p);
        assertThat(estado(e1, "Recebimento da solicitacao")).isEqualTo(EtapaFluxo.Estado.CONCLUIDA);
        assertThat(estado(e1, "Envio aos 3 medicos")).isEqualTo(EtapaFluxo.Estado.ATUAL);
        assertThat(estado(e1, "Respostas dos medicos")).isEqualTo(EtapaFluxo.Estado.PENDENTE);

        // etapa 2 completa -> etapa 3 fica ATUAL
        registrarEnvioCompleto(p);
        p.setStatus(StatusProcesso.ENVIADO);
        List<EtapaFluxo> e2 = fluxo().montarEtapas(p);
        assertThat(estado(e2, "Envio aos 3 medicos")).isEqualTo(EtapaFluxo.Estado.CONCLUIDA);
        assertThat(estado(e2, "Respostas dos medicos")).isEqualTo(EtapaFluxo.Estado.ATUAL);
        assertThat(estado(e2, "Decisao final")).isEqualTo(EtapaFluxo.Estado.PENDENTE);

        // etapa 3 completa (maioria favoravel) -> Decisao fica ATUAL
        registrarMaioria(p, ResultadoParecer.FAVORAVEL);
        List<EtapaFluxo> e3 = fluxo().montarEtapas(p);
        assertThat(estado(e3, "Respostas dos medicos")).isEqualTo(EtapaFluxo.Estado.CONCLUIDA);
        assertThat(estado(e3, "Decisao final")).isEqualTo(EtapaFluxo.Estado.ATUAL);

        // decisao tomada (Deferido) -> Comprovante SNT fica ATUAL, Resposta ao
        // solicitante ainda PENDENTE
        p.setStatus(StatusProcesso.DEFERIDO);
        List<EtapaFluxo> e4 = fluxo().montarEtapas(p);
        assertThat(estado(e4, "Decisao final")).isEqualTo(EtapaFluxo.Estado.CONCLUIDA);
        assertThat(estado(e4, "Comprovante SNT")).isEqualTo(EtapaFluxo.Estado.ATUAL);
        assertThat(estado(e4, "Resposta ao solicitante")).isEqualTo(EtapaFluxo.Estado.PENDENTE);

        // comprovante SNT anexado -> Resposta ao solicitante fica ATUAL
        Anexo comprovanteSnt = new Anexo();
        comprovanteSnt.setTipo(TipoAnexo.COMPROVANTE_SNT);
        p.addAnexo(comprovanteSnt);
        List<EtapaFluxo> e5 = fluxo().montarEtapas(p);
        assertThat(estado(e5, "Comprovante SNT")).isEqualTo(EtapaFluxo.Estado.CONCLUIDA);
        assertThat(estado(e5, "Resposta ao solicitante")).isEqualTo(EtapaFluxo.Estado.ATUAL);

        // e-mail + comprovante de envio -> Resposta ao solicitante CONCLUIDA, e
        // e a UNICA etapa concluida ao final: todas as demais tambem devem
        // estar CONCLUIDA (fim de fila).
        p.setEmailEnviadoSolicitante(true);
        Anexo comprovanteEnvio = new Anexo();
        comprovanteEnvio.setTipo(TipoAnexo.COMPROVANTE_ENVIO_SOLICITANTE);
        p.addAnexo(comprovanteEnvio);
        List<EtapaFluxo> eFinal = fluxo().montarEtapas(p);
        assertThat(eFinal).allSatisfy(e ->
            assertThat(e.estado()).isEqualTo(EtapaFluxo.Estado.CONCLUIDA));
        assertThat(fluxo().resumoPendencia(p)).isEqualTo("Processo concluido.");
    }

    // ----------------------------------------------------------------
    // Fluxo feliz completo: caso Indeferido, com o oficio.
    // ----------------------------------------------------------------

    @Test
    void fluxoFelizCompletoIndeferido() {
        Processo p = processoComTresPareceres();
        anexarRecebimentoCompleto(p);
        registrarEnvioCompleto(p);
        registrarMaioria(p, ResultadoParecer.NAO_FAVORAVEL);
        p.setStatus(StatusProcesso.INDEFERIDO);

        // decisao concluida, oficio ainda pendente/atual, sem etapa Comprovante SNT
        List<EtapaFluxo> e0 = fluxo().montarEtapas(p);
        assertThat(estado(e0, "Decisao final")).isEqualTo(EtapaFluxo.Estado.CONCLUIDA);
        assertThat(estado(e0, "Oficio de indeferimento")).isEqualTo(EtapaFluxo.Estado.ATUAL);
        assertThat(e0.stream().anyMatch(e -> e.titulo().equals("Comprovante SNT"))).isFalse();
        assertThat(estado(e0, "Resposta ao solicitante")).isEqualTo(EtapaFluxo.Estado.PENDENTE);

        // oficio parcialmente preenchido (so o motivo) -> continua nao concluido
        p.setMotivoIndeferimento("Documentacao incompleta");
        List<EtapaFluxo> e1 = fluxo().montarEtapas(p);
        assertThat(estado(e1, "Oficio de indeferimento")).isEqualTo(EtapaFluxo.Estado.ATUAL);
        assertThat(estado(e1, "Resposta ao solicitante")).isEqualTo(EtapaFluxo.Estado.PENDENTE);

        // oficio completo (motivo + anexo + data) -> libera Resposta ao solicitante
        Anexo oficio = new Anexo();
        oficio.setTipo(TipoAnexo.OFICIO_INDEFERIMENTO);
        p.addAnexo(oficio);
        p.setDataEmissaoOficio(LocalDate.now());
        List<EtapaFluxo> e2 = fluxo().montarEtapas(p);
        assertThat(estado(e2, "Oficio de indeferimento")).isEqualTo(EtapaFluxo.Estado.CONCLUIDA);
        assertThat(estado(e2, "Resposta ao solicitante")).isEqualTo(EtapaFluxo.Estado.ATUAL);
    }

    // ----------------------------------------------------------------
    // Casos "fora de ordem": mesma familia do bug original, mas exercitando
    // o oficio de indeferimento em vez do comprovante SNT.
    // ----------------------------------------------------------------

    @Test
    void oficioIndeferimentoCompletoNaoAdiantaRespostaSeDecisaoAindaNaoConcluida() {
        // Oficio com todos os dados presentes, mas o processo ainda NAO esta
        // com status INDEFERIDO (decisao nao tomada) - simulando dados
        // remanescentes de um fluxo anterior/incompleto. A etapa Oficio nem
        // deveria aparecer (so aparece com status INDEFERIDO), e a Decisao
        // final continua nao concluida.
        Processo p = processoComTresPareceres();
        anexarRecebimentoCompleto(p);
        registrarEnvioCompleto(p);
        registrarMaioria(p, ResultadoParecer.NAO_FAVORAVEL);
        p.setStatus(StatusProcesso.ENVIADO); // decisao ainda nao tomada
        p.setMotivoIndeferimento("motivo antigo");
        Anexo oficio = new Anexo();
        oficio.setTipo(TipoAnexo.OFICIO_INDEFERIMENTO);
        p.addAnexo(oficio);
        p.setDataEmissaoOficio(LocalDate.now());

        List<EtapaFluxo> etapas = fluxo().montarEtapas(p);
        assertThat(etapas.stream().anyMatch(e -> e.titulo().contains("Oficio"))).isFalse();
        assertThat(estado(etapas, "Decisao final")).isEqualTo(EtapaFluxo.Estado.ATUAL);
    }

    @Test
    void respostaAoSolicitanteMarcadaAntesDoOficioNaoFicaVerdeIndeferido() {
        // Variante do bug original para o ramo Indeferido: e-mail/comprovante
        // ao solicitante ja registrados, mas o oficio de indeferimento (etapa
        // anterior) ainda esta incompleto.
        Processo p = processoComTresPareceres();
        anexarRecebimentoCompleto(p);
        registrarEnvioCompleto(p);
        registrarMaioria(p, ResultadoParecer.NAO_FAVORAVEL);
        p.setStatus(StatusProcesso.INDEFERIDO);
        // oficio incompleto: falta o anexo
        p.setMotivoIndeferimento("Documentacao incompleta");
        p.setDataEmissaoOficio(LocalDate.now());
        // mas a resposta ao solicitante ja foi registrada
        p.setEmailEnviadoSolicitante(true);
        Anexo comprovanteEnvio = new Anexo();
        comprovanteEnvio.setTipo(TipoAnexo.COMPROVANTE_ENVIO_SOLICITANTE);
        p.addAnexo(comprovanteEnvio);

        List<EtapaFluxo> etapas = fluxo().montarEtapas(p);
        assertThat(estado(etapas, "Oficio de indeferimento")).isEqualTo(EtapaFluxo.Estado.ATUAL);
        assertThat(estado(etapas, "Resposta ao solicitante")).isEqualTo(EtapaFluxo.Estado.PENDENTE);
    }

    // ----------------------------------------------------------------
    // SOLICITA_INFORMACAO pausando um fluxo em andamento e retomando.
    // ----------------------------------------------------------------

    @Test
    void solicitaInformacaoAntesDeQualquerVotoMantemInformacaoComplementarPendente() {
        // Nenhuma maioria formada ainda (so 1 dos 3 respondeu, pedindo info):
        // a etapa "Respostas dos medicos" (anterior na timeline) continua sem
        // concluir - entao "Informacao complementar" fica PENDENTE, nao ATUAL.
        // Isso e esperado (nao e o bug de "etapa fora de ordem"): a timeline so
        // acende a etapa de pausa quando o fluxo realmente "chegou" nela.
        Processo p = processoComTresPareceres();
        anexarRecebimentoCompleto(p);
        registrarEnvioCompleto(p);
        p.setStatus(StatusProcesso.ENVIADO);
        Parecer par0 = p.getPareceres().get(0);
        par0.setId(1L);
        par0.setResultado(ResultadoParecer.SOLICITA_INFORMACAO);
        Anexo respPar0 = new Anexo();
        respPar0.setTipo(TipoAnexo.RESPOSTA_AVALIADOR);
        respPar0.setParecer(par0);
        p.addAnexo(respPar0);
        p.setStatus(StatusProcesso.SOLICITA_INFORMACAO);

        List<EtapaFluxo> etapas = fluxo().montarEtapas(p);
        assertThat(estado(etapas, "Recebimento da solicitacao")).isEqualTo(EtapaFluxo.Estado.CONCLUIDA);
        assertThat(estado(etapas, "Envio aos 3 medicos")).isEqualTo(EtapaFluxo.Estado.CONCLUIDA);
        assertThat(estado(etapas, "Respostas dos medicos")).isNotEqualTo(EtapaFluxo.Estado.CONCLUIDA);
        assertThat(estado(etapas, "Informacao complementar")).isEqualTo(EtapaFluxo.Estado.PENDENTE);
        assertThat(estado(etapas, "Decisao final")).isEqualTo(EtapaFluxo.Estado.PENDENTE);
    }

    @Test
    void solicitaInformacaoComMaioriaJaFormadaPausaDecisaoEDepoisRetoma() {
        // Maioria ja formada (2 favoraveis) mas o 3o medico pede informacao
        // complementar em vez de votar: atualizarStatusPorPareceres da
        // prioridade ao pedido de informacao (pausa sempre, mesmo com maioria).
        // Neste caso a etapa "Respostas dos medicos" ESTA concluida (maioria +
        // anexos de todos os 3 pareceres recebidos), entao "Informacao
        // complementar" fica ATUAL (a pausa esta "na frente" do fluxo).
        Processo p = processoComTresPareceres();
        anexarRecebimentoCompleto(p);
        registrarEnvioCompleto(p);
        registrarMaioria(p, ResultadoParecer.FAVORAVEL); // pareceres 0 e 1 favoraveis, com anexo
        Parecer par2 = p.getPareceres().get(2);
        par2.setId(3L);
        par2.setResultado(ResultadoParecer.SOLICITA_INFORMACAO);
        Anexo respPar2 = new Anexo();
        respPar2.setTipo(TipoAnexo.RESPOSTA_AVALIADOR);
        respPar2.setParecer(par2);
        p.addAnexo(respPar2);
        p.setStatus(StatusProcesso.SOLICITA_INFORMACAO);

        List<EtapaFluxo> pausado = fluxo().montarEtapas(p);
        assertThat(estado(pausado, "Respostas dos medicos")).isEqualTo(EtapaFluxo.Estado.CONCLUIDA);
        assertThat(estado(pausado, "Informacao complementar")).isEqualTo(EtapaFluxo.Estado.ATUAL);
        // Decisao continua bloqueada mesmo com maioria formada: a pausa vence.
        assertThat(estado(pausado, "Decisao final")).isEqualTo(EtapaFluxo.Estado.PENDENTE);
        assertThat(fluxo().resumoPendencia(p)).startsWith("Informacao complementar");

        // Retoma: ProcessoService.retomarAposInformacao volta o status para
        // ENVIADO e limpa (reabre) apenas o parecer que pediu informacao.
        ProcessoService ps = new ProcessoService(processoRepository, membroRepository, new ProcessoValidator());
        org.mockito.Mockito.when(processoRepository.findById(org.mockito.ArgumentMatchers.anyLong()))
            .thenReturn(java.util.Optional.of(p));
        org.mockito.Mockito.when(processoRepository.save(org.mockito.ArgumentMatchers.any()))
            .thenAnswer(inv -> inv.getArgument(0));
        p.setId(1L);
        Processo retomado = ps.retomarAposInformacao(1L);

        assertThat(retomado.getStatus()).isEqualTo(StatusProcesso.ENVIADO);
        assertThat(par2.getResultado()).isNull();
        // os 2 votos favoraveis originais nao sao afetados pela retomada
        assertThat(p.getPareceres().get(0).getResultado()).isEqualTo(ResultadoParecer.FAVORAVEL);

        List<EtapaFluxo> apos = fluxo().montarEtapas(retomado);
        assertThat(apos.stream().anyMatch(e -> e.titulo().equals("Informacao complementar"))).isFalse();
        // A maioria dos 2 favoraveis originais continua de pe: Respostas
        // permanece concluida e a Decisao fica liberada (ATUAL) imediatamente.
        assertThat(estado(apos, "Respostas dos medicos")).isEqualTo(EtapaFluxo.Estado.CONCLUIDA);
        assertThat(estado(apos, "Decisao final")).isEqualTo(EtapaFluxo.Estado.ATUAL);
    }

    // ----------------------------------------------------------------
    // Wizard horizontal x timeline vertical: bug de dessincronia.
    // ----------------------------------------------------------------

    @Test
    void wizardBloqueiaDecisaoQuandoSolicitaInformacaoComMaioriaJaFormada() {
        // Mesmo cenario de solicitaInformacaoComMaioriaJaFormadaPausaDecisaoEDepoisRetoma:
        // maioria ja formada (Respostas = CONCLUIDA), mas o 3o medico pediu
        // informacao complementar, pausando o fluxo. A timeline vertical
        // bloqueia "Decisao final" (PENDENTE) - o wizard horizontal precisa
        // concordar e manter o passo 4 BLOQUEADA, nao ATUAL.
        Processo p = processoComTresPareceres();
        anexarRecebimentoCompleto(p);
        registrarEnvioCompleto(p);
        registrarMaioria(p, ResultadoParecer.FAVORAVEL);
        Parecer par2 = p.getPareceres().get(2);
        par2.setId(3L);
        par2.setResultado(ResultadoParecer.SOLICITA_INFORMACAO);
        Anexo respPar2 = new Anexo();
        respPar2.setTipo(TipoAnexo.RESPOSTA_AVALIADOR);
        respPar2.setParecer(par2);
        p.addAnexo(respPar2);
        p.setStatus(StatusProcesso.SOLICITA_INFORMACAO);

        List<EtapaFluxo> etapas = fluxo().montarEtapas(p);
        assertThat(estado(etapas, "Decisao final")).isEqualTo(EtapaFluxo.Estado.PENDENTE);

        List<PassoWizard> passos = fluxo().montarPassosWizard(p);
        PassoWizard passoDecisao = passos.stream()
            .filter(passo -> passo.numero() == 4).findFirst().orElseThrow();
        assertThat(passoDecisao.estado()).isEqualTo(PassoWizard.Estado.BLOQUEADA);
    }

    // ----------------------------------------------------------------
    // etapasConcluidas / progresso: mesma logica usada por
    // ProcessoDetalheController.detalhe() (conta estados == CONCLUIDA).
    // ----------------------------------------------------------------

    @Test
    void contagemDeEtapasConcluidasBateComEstadoReal() {
        Processo p = processoProntoParaDecisao();
        p.setStatus(StatusProcesso.DEFERIDO);
        Anexo comprovanteSnt = new Anexo();
        comprovanteSnt.setTipo(TipoAnexo.COMPROVANTE_SNT);
        p.addAnexo(comprovanteSnt);
        // Resposta ao solicitante NAO registrada.

        List<EtapaFluxo> etapas = fluxo().montarEtapas(p);
        long concluidasEsperadas = etapas.stream()
            .filter(e -> e.estado() == EtapaFluxo.Estado.CONCLUIDA).count();

        // Recebimento, Envio, Respostas, Decisao, Comprovante SNT = 5 concluidas;
        // Resposta ao solicitante fica ATUAL (nao concluida). Total de etapas = 6
        // (sem a etapa de Informacao complementar, que so aparece em pausa).
        assertThat(etapas).hasSize(6);
        assertThat(concluidasEsperadas).isEqualTo(5);
        assertThat(estado(etapas, "Resposta ao solicitante")).isEqualTo(EtapaFluxo.Estado.ATUAL);
    }

    @Test
    void resumoPendenciaApontaComprovanteSntQuandoDeferidoSemComprovante() {
        Processo p = processoProntoParaDecisao();
        p.setStatus(StatusProcesso.DEFERIDO);
        assertThat(fluxo().resumoPendencia(p)).startsWith("Comprovante SNT");
    }

    @Test
    void resumoPendenciaApontaOficioQuandoIndeferidoSemOficio() {
        Processo p = processoComTresPareceres();
        anexarRecebimentoCompleto(p);
        registrarEnvioCompleto(p);
        registrarMaioria(p, ResultadoParecer.NAO_FAVORAVEL);
        p.setStatus(StatusProcesso.INDEFERIDO);
        assertThat(fluxo().resumoPendencia(p)).startsWith("Oficio de indeferimento");
    }

    @Test
    void resumoPendenciaApontaRespostaAoSolicitanteComoUltimaEtapa() {
        Processo p = processoProntoParaDecisao();
        p.setStatus(StatusProcesso.DEFERIDO);
        Anexo comprovanteSnt = new Anexo();
        comprovanteSnt.setTipo(TipoAnexo.COMPROVANTE_SNT);
        p.addAnexo(comprovanteSnt);
        assertThat(fluxo().resumoPendencia(p)).startsWith("Resposta ao solicitante");
    }

    private EtapaFluxo.Estado estado(List<EtapaFluxo> etapas, String titulo) {
        return etapas.stream()
            .filter(e -> e.titulo().equals(titulo))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Etapa nao encontrada: " + titulo))
            .estado();
    }
}
