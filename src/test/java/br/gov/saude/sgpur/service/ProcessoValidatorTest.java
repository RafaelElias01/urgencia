package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Suite dedicada de ProcessoValidator - antes so era testado indiretamente
 * via ProcessoServiceTest. Cobre cada regra isolada, incluindo o bug real
 * corrigido em validarPausaDecisao (coordenador nao pode indeferir em pausa).
 */
class ProcessoValidatorTest {

    private final ProcessoValidator validator = new ProcessoValidator();

    private MembroUrgenciaRenal medico(boolean coordenador) {
        MembroUrgenciaRenal m = new MembroUrgenciaRenal("HCPA", "Medico", null);
        m.setCoordenador(coordenador);
        return m;
    }

    private Parecer parecer(ResultadoParecer resultado, boolean coordenador) {
        Parecer p = new Parecer(medico(coordenador));
        p.setResultado(resultado);
        return p;
    }

    private void anexarResposta(Processo processo, Parecer parecer) {
        parecer.setId((long) (processo.getPareceres().indexOf(parecer) + 1));
        Anexo a = new Anexo();
        a.setTipo(TipoAnexo.RESPOSTA_AVALIADOR);
        a.setParecer(parecer);
        processo.addAnexo(a);
    }

    // ----- edicaoBloqueada -----

    @Test
    void edicaoBloqueadaTrueQuandoFinalizado() {
        Processo p = new Processo();
        p.setStatus(StatusProcesso.DEFERIDO);
        assertThat(validator.edicaoBloqueada(p)).isTrue();
    }

    @Test
    void edicaoBloqueadaFalseQuandoEmAndamento() {
        Processo p = new Processo();
        p.setStatus(StatusProcesso.ENVIADO);
        assertThat(validator.edicaoBloqueada(p)).isFalse();
    }

    // ----- contagens -----

    @Test
    void contagensDeVotos() {
        Processo p = new Processo();
        p.addParecer(parecer(ResultadoParecer.FAVORAVEL, false));
        p.addParecer(parecer(ResultadoParecer.FAVORAVEL, false));
        p.addParecer(parecer(ResultadoParecer.NAO_FAVORAVEL, false));

        assertThat(validator.contarFavoraveis(p)).isEqualTo(2);
        assertThat(validator.contarNaoFavoraveis(p)).isEqualTo(1);
        assertThat(validator.contarRespondidos(p)).isEqualTo(3);
    }

    @Test
    void naoRespondidoNaoContaEmNenhumaCategoria() {
        Processo p = new Processo();
        p.addParecer(parecer(null, false));
        assertThat(validator.contarRespondidos(p)).isZero();
        assertThat(validator.contarFavoraveis(p)).isZero();
    }

    // ----- coordenador -----

    @Test
    void temVotoCoordenadorFavoravelSoContaSeForRealmenteCoordenador() {
        Processo p = new Processo();
        p.addParecer(parecer(ResultadoParecer.FAVORAVEL, false));
        assertThat(validator.temVotoCoordenadorFavoravel(p)).isFalse();

        p.addParecer(parecer(ResultadoParecer.FAVORAVEL, true));
        assertThat(validator.temVotoCoordenadorFavoravel(p)).isTrue();
    }

    @Test
    void temVotoCoordenadorFavoravelFalseSeCoordenadorVotouDesfavoravel() {
        Processo p = new Processo();
        p.addParecer(parecer(ResultadoParecer.NAO_FAVORAVEL, true));
        assertThat(validator.temVotoCoordenadorFavoravel(p)).isFalse();
    }

    @Test
    void deferidoPeloCoordenadorExigeStatusDeferidoEVotoCoordenador() {
        Processo p = new Processo();
        p.addParecer(parecer(ResultadoParecer.FAVORAVEL, true));

        p.setStatus(StatusProcesso.ENVIADO);
        assertThat(validator.deferidoPeloCoordenador(p)).isFalse();

        p.setStatus(StatusProcesso.DEFERIDO);
        assertThat(validator.deferidoPeloCoordenador(p)).isTrue();
    }

    @Test
    void favoraveisNecessariosParaDeferirCaiParaUmComCoordenador() {
        Processo p = new Processo();
        assertThat(validator.favoraveisNecessariosParaDeferir(p))
            .isEqualTo(ProcessoService.FAVORAVEIS_PARA_DEFERIR);

        p.addParecer(parecer(ResultadoParecer.FAVORAVEL, true));
        assertThat(validator.favoraveisNecessariosParaDeferir(p)).isEqualTo(1);
    }

    // ----- sugerirDecisao -----

    @Test
    void sugerirDecisaoVazioSemMaioria() {
        Processo p = new Processo();
        p.addParecer(parecer(ResultadoParecer.FAVORAVEL, false));
        assertThat(validator.sugerirDecisao(p)).isEmpty();
    }

    @Test
    void sugerirDecisaoDeferidoComMaioriaSimples() {
        Processo p = new Processo();
        p.addParecer(parecer(ResultadoParecer.FAVORAVEL, false));
        p.addParecer(parecer(ResultadoParecer.FAVORAVEL, false));
        assertThat(validator.sugerirDecisao(p)).contains(StatusProcesso.DEFERIDO);
    }

    @Test
    void sugerirDecisaoIndeferidoComMaioriaSimples() {
        Processo p = new Processo();
        p.addParecer(parecer(ResultadoParecer.NAO_FAVORAVEL, false));
        p.addParecer(parecer(ResultadoParecer.NAO_FAVORAVEL, false));
        assertThat(validator.sugerirDecisao(p)).contains(StatusProcesso.INDEFERIDO);
    }

    @Test
    void sugerirDecisaoDeferidoComUmUnicoVotoDoCoordenador() {
        Processo p = new Processo();
        p.addParecer(parecer(ResultadoParecer.FAVORAVEL, true));
        assertThat(validator.sugerirDecisao(p)).contains(StatusProcesso.DEFERIDO);
    }

    @Test
    void sugerirDecisaoCoordenadorFavoravelVenceMesmoComDoisDesfavoraveis() {
        // O peso unico do coordenador prevalece mesmo diante de maioria contraria.
        Processo p = new Processo();
        p.addParecer(parecer(ResultadoParecer.NAO_FAVORAVEL, false));
        p.addParecer(parecer(ResultadoParecer.NAO_FAVORAVEL, false));
        p.addParecer(parecer(ResultadoParecer.FAVORAVEL, true));
        assertThat(validator.sugerirDecisao(p)).contains(StatusProcesso.DEFERIDO);
    }

    // ----- pareceresRecebidosSemAnexo -----

    @Test
    void pareceresRecebidosSemAnexoIgnoraVotoDireitoPeloPortal() {
        Processo p = new Processo();
        Parecer viaPortal = parecer(ResultadoParecer.FAVORAVEL, false);
        viaPortal.setOrigem(OrigemParecer.AVALIADOR_SISTEMA);
        p.addParecer(viaPortal); // sem anexo, mas nao deveria contar

        assertThat(validator.pareceresRecebidosSemAnexo(p)).isEmpty();
    }

    @Test
    void pareceresRecebidosSemAnexoAcusaOperadorEmailSemComprovante() {
        Processo p = new Processo();
        Parecer viaEmail = parecer(ResultadoParecer.FAVORAVEL, false);
        viaEmail.setOrigem(OrigemParecer.OPERADOR_EMAIL);
        viaEmail.setId(1L);
        p.addParecer(viaEmail); // sem anexo

        List<Parecer> semAnexo = validator.pareceresRecebidosSemAnexo(p);
        assertThat(semAnexo).containsExactly(viaEmail);
    }

    @Test
    void pareceresRecebidosSemAnexoVazioAposAnexarResposta() {
        Processo p = new Processo();
        Parecer viaEmail = parecer(ResultadoParecer.FAVORAVEL, false);
        p.addParecer(viaEmail);
        anexarResposta(p, viaEmail);

        assertThat(validator.pareceresRecebidosSemAnexo(p)).isEmpty();
    }

    // ----- validarPausaDecisao (bug real corrigido aqui) -----

    @Test
    void validarPausaDecisaoBloqueiaDeferidoSemCoordenador() {
        Processo p = new Processo();
        p.setStatus(StatusProcesso.SOLICITA_INFORMACAO);
        assertThat(validator.validarPausaDecisao(p, StatusProcesso.DEFERIDO)).isPresent();
    }

    @Test
    void validarPausaDecisaoLiberaDeferidoComCoordenadorFavoravel() {
        Processo p = new Processo();
        p.setStatus(StatusProcesso.SOLICITA_INFORMACAO);
        p.addParecer(parecer(ResultadoParecer.FAVORAVEL, true));
        assertThat(validator.validarPausaDecisao(p, StatusProcesso.DEFERIDO)).isEmpty();
    }

    @Test
    void validarPausaDecisaoBloqueiaIndeferidoMesmoComCoordenadorFavoravel() {
        // Bug real: o coordenador NAO tem peso especial para indeferir. Mesmo
        // com o coordenador favoravel registrado, Indeferido continua
        // bloqueado enquanto o processo estiver pausado.
        Processo p = new Processo();
        p.setStatus(StatusProcesso.SOLICITA_INFORMACAO);
        p.addParecer(parecer(ResultadoParecer.FAVORAVEL, true));

        assertThat(validator.validarPausaDecisao(p, StatusProcesso.INDEFERIDO)).isPresent();
    }

    @Test
    void validarPausaDecisaoNaoBloqueiaForaDePausa() {
        Processo p = new Processo();
        p.setStatus(StatusProcesso.ENVIADO);
        assertThat(validator.validarPausaDecisao(p, StatusProcesso.DEFERIDO)).isEmpty();
        assertThat(validator.validarPausaDecisao(p, StatusProcesso.INDEFERIDO)).isEmpty();
    }

    // ----- validarContagemVotos -----

    @Test
    void validarContagemVotosBloqueiaDeferidoSemMaioria() {
        Processo p = new Processo();
        p.addParecer(parecer(ResultadoParecer.FAVORAVEL, false));
        assertThat(validator.validarContagemVotos(p, StatusProcesso.DEFERIDO)).isPresent();
    }

    @Test
    void validarContagemVotosLiberaDeferidoComUmVotoDeCoordenador() {
        Processo p = new Processo();
        p.addParecer(parecer(ResultadoParecer.FAVORAVEL, true));
        assertThat(validator.validarContagemVotos(p, StatusProcesso.DEFERIDO)).isEmpty();
    }

    @Test
    void validarContagemVotosBloqueiaIndeferidoSemDoisDesfavoraveis() {
        Processo p = new Processo();
        p.addParecer(parecer(ResultadoParecer.NAO_FAVORAVEL, false));
        assertThat(validator.validarContagemVotos(p, StatusProcesso.INDEFERIDO)).isPresent();
    }

    @Test
    void validarContagemVotosIndeferidoIgnoraVotoDoCoordenador() {
        // O coordenador nao reduz a exigencia de Indeferido (sempre 2, mesmo
        // que ele tenha votado desfavoravel).
        Processo p = new Processo();
        p.addParecer(parecer(ResultadoParecer.NAO_FAVORAVEL, true));
        assertThat(validator.validarContagemVotos(p, StatusProcesso.INDEFERIDO)).isPresent();
    }

    // ----- validarMotivoIndeferimento -----

    @Test
    void validarMotivoIndeferimentoExigidoSoParaIndeferido() {
        assertThat(validator.validarMotivoIndeferimento(StatusProcesso.INDEFERIDO, null)).isPresent();
        assertThat(validator.validarMotivoIndeferimento(StatusProcesso.INDEFERIDO, "  ")).isPresent();
        assertThat(validator.validarMotivoIndeferimento(StatusProcesso.INDEFERIDO, "motivo")).isEmpty();
        assertThat(validator.validarMotivoIndeferimento(StatusProcesso.DEFERIDO, null)).isEmpty();
    }

    // ----- validarAnexosResposta -----

    @Test
    void validarAnexosRespostaBloqueiaSePareceresSemAnexo() {
        Processo p = new Processo();
        Parecer viaEmail = parecer(ResultadoParecer.FAVORAVEL, false);
        viaEmail.setId(1L);
        p.addParecer(viaEmail);

        assertThat(validator.validarAnexosResposta(p, StatusProcesso.DEFERIDO)).isPresent();
    }

    @Test
    void validarAnexosRespostaLiberaComAnexoPresente() {
        Processo p = new Processo();
        Parecer viaEmail = parecer(ResultadoParecer.FAVORAVEL, false);
        p.addParecer(viaEmail);
        anexarResposta(p, viaEmail);

        assertThat(validator.validarAnexosResposta(p, StatusProcesso.DEFERIDO)).isEmpty();
    }

    // ----- validarDecisao (encadeamento completo) -----

    @Test
    void validarDecisaoRetornaPrimeiroErroNaOrdemPausaContagemMotivoAnexos() {
        // pausado E sem votos suficientes: deve reportar a pausa primeiro.
        Processo p = new Processo();
        p.setStatus(StatusProcesso.SOLICITA_INFORMACAO);

        assertThat(validator.validarDecisao(p, StatusProcesso.DEFERIDO, null))
            .get(org.assertj.core.api.InstanceOfAssertFactories.STRING)
            .contains("informacao complementar");
    }

    @Test
    void validarDecisaoVazioQuandoTudoOk() {
        Processo p = new Processo();
        p.setStatus(StatusProcesso.ENVIADO);
        Parecer p1 = parecer(ResultadoParecer.FAVORAVEL, false);
        Parecer p2 = parecer(ResultadoParecer.FAVORAVEL, false);
        p.addParecer(p1);
        p.addParecer(p2);
        anexarResposta(p, p1);
        anexarResposta(p, p2);

        assertThat(validator.validarDecisao(p, StatusProcesso.DEFERIDO, null)).isEmpty();
    }

    // ----- validarRespostaSolicitante -----

    @Test
    void validarRespostaSolicitanteExigeComprovanteSntSeDeferido() {
        Processo p = new Processo();
        p.setStatus(StatusProcesso.DEFERIDO);
        assertThat(validator.validarRespostaSolicitante(p)).isPresent();

        Anexo comprovante = new Anexo();
        comprovante.setTipo(TipoAnexo.COMPROVANTE_SNT);
        p.addAnexo(comprovante);
        assertThat(validator.validarRespostaSolicitante(p)).isEmpty();
    }

    @Test
    void validarRespostaSolicitanteExigeOficioSeIndeferido() {
        Processo p = new Processo();
        p.setStatus(StatusProcesso.INDEFERIDO);
        assertThat(validator.validarRespostaSolicitante(p)).isPresent();

        Anexo oficio = new Anexo();
        oficio.setTipo(TipoAnexo.OFICIO_INDEFERIMENTO);
        p.addAnexo(oficio);
        assertThat(validator.validarRespostaSolicitante(p)).isEmpty();
    }

    @Test
    void validarRespostaSolicitanteVazioForaDeStatusFinal() {
        Processo p = new Processo();
        p.setStatus(StatusProcesso.ENVIADO);
        assertThat(validator.validarRespostaSolicitante(p)).isEmpty();
    }
}
