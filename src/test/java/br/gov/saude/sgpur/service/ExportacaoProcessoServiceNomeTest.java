package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.Processo;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sanitizacao do nome da pasta unica do dossie ("Exportar processo completo").
 * O nome vai virar uma PASTA REAL no Windows do operador quando ele
 * descompactar o ZIP, entao nao pode conter os caracteres proibidos
 * ({@code \ / : * ? " < > |}) nem terminar em ponto/espaco.
 */
class ExportacaoProcessoServiceNomeTest {

    private static Processo processo(String paciente, String numero) {
        Processo p = new Processo();
        p.setPacienteNome(paciente);
        p.setNumero(numero);
        return p;
    }

    @Test
    void nomeDaPastaSegueOFormatoOficial() {
        assertThat(ExportacaoProcessoService.nomePasta(processo("Maria Souza da Silva", "07/2026")))
            .isEqualTo("Maria Souza da Silva - Processo CET-RS 07-2026");
    }

    @Test
    void barraDoNumeroViraTraco() {
        assertThat(ExportacaoProcessoService.nomePasta(processo("Joao", "01/2027")))
            .endsWith("Processo CET-RS 01-2027")
            .doesNotContain("/");
    }

    @Test
    void removeCaracteresProibidosNoWindows() {
        String nome = ExportacaoProcessoService.nomePasta(
            processo("Ana \\ Maria / Jose : *?\"<>| Silva", "02/2026"));
        for (char c : new char[]{'\\', '/', ':', '*', '?', '"', '<', '>', '|'}) {
            assertThat(nome).doesNotContain(String.valueOf(c));
        }
        assertThat(nome).isEqualTo("Ana Maria Jose Silva - Processo CET-RS 02-2026");
    }

    @Test
    void colapsaEspacosEPreservaAcentos() {
        assertThat(ExportacaoProcessoService.nomePasta(processo("  Jose   Antonio  ", "03/2026")))
            .isEqualTo("Jose Antonio - Processo CET-RS 03-2026");
        assertThat(ExportacaoProcessoService.sanitizar("Conceicao Aparecida"))
            .isEqualTo("Conceicao Aparecida");
    }

    @Test
    void naoTerminaComPontoNemEspaco() {
        assertThat(ExportacaoProcessoService.sanitizar("Maria Silva Jr. ")).isEqualTo("Maria Silva Jr");
        assertThat(ExportacaoProcessoService.sanitizar("teste...")).isEqualTo("teste");
    }

    @Test
    void usaFallbackQuandoFaltamPacienteOuNumero() {
        assertThat(ExportacaoProcessoService.nomePasta(processo(null, null)))
            .isEqualTo("Paciente - Processo CET-RS SN");
        assertThat(ExportacaoProcessoService.nomePasta(processo("   ", "  ")))
            .isEqualTo("Paciente - Processo CET-RS SN");
    }

    @Test
    void nomeUnicoAcrescentaSufixoNumeradoAntesDaExtensao() {
        Set<String> usados = new HashSet<>();
        assertThat(ExportacaoProcessoService.nomeUnico(usados, "exame.pdf")).isEqualTo("exame.pdf");
        assertThat(ExportacaoProcessoService.nomeUnico(usados, "exame.pdf")).isEqualTo("exame (2).pdf");
        assertThat(ExportacaoProcessoService.nomeUnico(usados, "exame.pdf")).isEqualTo("exame (3).pdf");
        assertThat(ExportacaoProcessoService.nomeUnico(usados, "outro.pdf")).isEqualTo("outro.pdf");
    }
}
