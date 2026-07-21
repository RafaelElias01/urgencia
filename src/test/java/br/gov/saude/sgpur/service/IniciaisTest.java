package br.gov.saude.sgpur.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IniciaisTest {

    @Test
    void geraIniciaisIgnorandoConectivos() {
        assertThat(Iniciais.de("Mariana da Rosa Martins")).isEqualTo("M.R.M.");
        assertThat(Iniciais.de("Joao Paciente Secreto")).isEqualTo("J.P.S.");
        assertThat(Iniciais.de("Ana Cristina Freitas da Silva")).isEqualTo("A.C.F.S.");
    }

    @Test
    void lidaComAcentosEEspacos() {
        assertThat(Iniciais.de("  Iclêdes   Maria  Matte ")).isEqualTo("I.M.M.");
    }

    @Test
    void retornaVazioParaNuloOuEmBranco() {
        assertThat(Iniciais.de(null)).isEmpty();
        assertThat(Iniciais.de("   ")).isEmpty();
    }
}
