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
        Processo p = processoComTresPareceres();
        p.setStatus(StatusProcesso.ENVIADO);
        // Etapa 1 (Recebimento) concluida: solicitacao original + capa.
        Anexo original = new Anexo();
        original.setTipo(TipoAnexo.SOLICITACAO_RECEBIDA);
        p.addAnexo(original);
        Anexo capa = new Anexo();
        capa.setTipo(TipoAnexo.CAPA_PROCESSO);
        p.addAnexo(capa);
        long id = 1;
        for (Parecer par : p.getPareceres()) {
            par.setId(id++);
            par.setDataEnvio(LocalDate.now());
        }
        // dois votos favoraveis com seus anexos de resposta vinculados
        for (int i = 0; i < 2; i++) {
            Parecer par = p.getPareceres().get(i);
            par.setResultado(ResultadoParecer.FAVORAVEL);
            Anexo resp = new Anexo();
            resp.setTipo(TipoAnexo.RESPOSTA_AVALIADOR);
            resp.setParecer(par);
            p.addAnexo(resp);
        }
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
        Processo p = processoComTresPareceres();
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
}
