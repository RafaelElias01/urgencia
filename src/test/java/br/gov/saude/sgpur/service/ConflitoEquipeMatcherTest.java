package br.gov.saude.sgpur.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Casamento solicitante x avaliador para o aviso (nao-bloqueante) de conflito
 * de interesse. Ignora maiusculas/acentos; casa sigla x nome por extenso x
 * cidade via mapa de apelidos; exige palavra inteira (nao substring solto).
 */
class ConflitoEquipeMatcherTest {

    private final ConflitoEquipeMatcher matcher = new ConflitoEquipeMatcher();

    @Test
    void siglaIgualCasa() {
        assertThat(matcher.mesmaEquipe("HCPA", "HCPA")).isTrue();
        assertThat(matcher.mesmaEquipe("HCPA", "hcpa")).isTrue();
    }

    @Test
    void siglaCasaComNomePorExtenso() {
        assertThat(matcher.mesmaEquipe("HCPA", "Hospital de Clinicas de Porto Alegre")).isTrue();
        assertThat(matcher.mesmaEquipe("ISCMPA", "Santa Casa de Misericordia")).isTrue();
        assertThat(matcher.mesmaEquipe("HSLPUC", "Hospital Sao Lucas da PUC")).isTrue();
    }

    @Test
    void casaPorCidade() {
        assertThat(matcher.mesmaEquipe("HNSP", "Hospital Nossa Senhora da Pompeia - Caxias do Sul")).isTrue();
    }

    @Test
    void ignoraAcentosEMaiusculas() {
        assertThat(matcher.mesmaEquipe("HSLPUC", "HOSPITAL SÃO LUCAS")).isTrue();
        assertThat(matcher.mesmaEquipe("ISCMPA", "irmandade santa casa de misericórdia")).isTrue();
    }

    @Test
    void equipesDiferentesNaoCasa() {
        assertThat(matcher.mesmaEquipe("HCPA", "Hospital Sao Lucas")).isFalse();
        assertThat(matcher.mesmaEquipe("HNSP", "HCPA")).isFalse();
    }

    @Test
    void semHospitalNuncaCasa() {
        assertThat(matcher.mesmaEquipe("Sem Hospital", "Sem Hospital")).isFalse();
        assertThat(matcher.mesmaEquipe("Sem Hospital", "Hospital de Clinicas")).isFalse();
    }

    @Test
    void nulosOuVaziosNaoCasam() {
        assertThat(matcher.mesmaEquipe(null, "HCPA")).isFalse();
        assertThat(matcher.mesmaEquipe("HCPA", null)).isFalse();
        assertThat(matcher.mesmaEquipe("HCPA", "   ")).isFalse();
    }

    @Test
    void siglaForaDoMapaCasaPorTokenDaPropriaInstituicao() {
        // "HDI" nao esta no mapa; casa pelo proprio token quando o solicitante o repete.
        assertThat(matcher.mesmaEquipe("HDI Passo Fundo", "Hospital HDI de Passo Fundo")).isTrue();
        assertThat(matcher.mesmaEquipe("HDI Passo Fundo", "Hospital Moinhos de Vento")).isFalse();
    }
}
