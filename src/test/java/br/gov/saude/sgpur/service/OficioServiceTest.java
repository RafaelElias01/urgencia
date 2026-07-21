package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.Processo;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes do OficioService - gera o Oficio de Indeferimento em PDF. Como o
 * documento e curto e a "logica" real esta toda no texto embutido (motivo
 * com fallback, data de emissao com fallback, numero/paciente/solicitante),
 * os testes extraem o texto da pagina via PdfTextExtractor (mesmo padrao de
 * RelatorioAnualServiceTest/SolicitacaoAvaliadorServiceTest) em vez de so
 * checar que os bytes nao estao vazios.
 */
class OficioServiceTest {

    private final OficioService service = new OficioService();

    private Processo processoCompleto() {
        Processo p = new Processo();
        p.setNumero("07/2026");
        p.setPacienteNome("Fulano de Tal");
        p.setSolicitanteEquipe("Hospital de Clinicas");
        p.setMotivoIndeferimento("Ausencia de indicacao clinica para urgencia renal.");
        p.setDataEmissaoOficio(LocalDate.of(2026, 3, 15));
        return p;
    }

    private String textoDaPagina1(byte[] pdf) throws Exception {
        PdfReader reader = new PdfReader(pdf);
        try {
            return new PdfTextExtractor(reader).getTextFromPage(1);
        } finally {
            reader.close();
        }
    }

    @Test
    void geraPdfNaoVazioComAssinaturaPdf() {
        byte[] pdf = service.gerar(processoCompleto());

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
    }

    @Test
    void corpoContemNumeroPacienteESolicitante() throws Exception {
        Processo p = processoCompleto();

        String texto = textoDaPagina1(service.gerar(p));

        assertThat(texto)
            .contains("OFICIO DE INDEFERIMENTO")
            .contains(p.identificacao())
            .contains("Processo de Urgencia Renal n. 07/2026")
            .contains("Fulano de Tal")
            .contains("Ao(A) solicitante: Hospital de Clinicas")
            .contains("INDEFERIDO");
    }

    @Test
    void corpoContemMotivoInformado() throws Exception {
        Processo p = processoCompleto();

        String texto = textoDaPagina1(service.gerar(p));

        assertThat(texto).contains("Motivo do indeferimento: Ausencia de indicacao clinica para urgencia renal.");
    }

    @Test
    void usaPlaceholderQuandoMotivoNulo() throws Exception {
        Processo p = processoCompleto();
        p.setMotivoIndeferimento(null);

        String texto = textoDaPagina1(service.gerar(p));

        assertThat(texto).contains("Motivo do indeferimento: (motivo nao informado)");
    }

    @Test
    void usaPlaceholderQuandoMotivoEmBranco() throws Exception {
        Processo p = processoCompleto();
        p.setMotivoIndeferimento("   ");

        String texto = textoDaPagina1(service.gerar(p));

        assertThat(texto).contains("(motivo nao informado)");
    }

    @Test
    void usaDataEmissaoOficioQuandoPreenchida() throws Exception {
        Processo p = processoCompleto();
        p.setDataEmissaoOficio(LocalDate.of(2026, 1, 5));

        String texto = textoDaPagina1(service.gerar(p));

        // 5 de janeiro de 2026, por extenso em pt-BR
        assertThat(texto).contains("5 de janeiro de 2026");
    }

    @Test
    void caiParaDataDeHojeQuandoDataEmissaoOficioNaoPreenchida() throws Exception {
        Processo p = processoCompleto();
        p.setDataEmissaoOficio(null);
        LocalDate hoje = LocalDate.now();

        String texto = textoDaPagina1(service.gerar(p));

        assertThat(texto).contains(String.valueOf(hoje.getYear()));
        assertThat(texto).contains(String.valueOf(hoje.getDayOfMonth()));
    }

    @Test
    void identificacaoOmiteRgctQuandoNaoPreenchido() throws Exception {
        Processo p = processoCompleto();
        p.setPacienteRgct(null);

        String texto = textoDaPagina1(service.gerar(p));

        assertThat(texto).doesNotContain("RGCT");
        assertThat(texto).contains("07/2026 - Fulano de Tal");
    }

    @Test
    void identificacaoIncluiRgctQuandoPreenchido() throws Exception {
        Processo p = processoCompleto();
        p.setPacienteRgct("123456789");

        String texto = textoDaPagina1(service.gerar(p));

        assertThat(texto).contains("RGCT 123456789");
    }

    @Test
    void naoLancaExcecaoComProcessoMinimoSemNomeOuEquipe() {
        // Processo sem paciente/solicitante/motivo preenchidos: OficioService
        // nao valida campos obrigatorios antes de gerar - so usa fallback
        // (motivo) ou embute o que houver (mesmo que "null" no texto). O
        // servico nao deve lancar excecao nesse cenario.
        Processo p = new Processo();
        p.setNumero("01/2026");

        byte[] pdf = service.gerar(p);

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
    }

    @Test
    void cabecalhoInstitucionalPresente() throws Exception {
        String texto = textoDaPagina1(service.gerar(processoCompleto()));

        assertThat(texto)
            .contains("Central de Transplantes do Estado do Rio Grande do Sul")
            .contains("URGENCIA RENAL");
    }
}
