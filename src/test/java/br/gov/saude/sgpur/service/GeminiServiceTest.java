package br.gov.saude.sgpur.service;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes do GeminiService: o kill-switch (app.gemini.enabled) e a chave
 * ausente/em branco devem retornar Optional.empty() SEM tentar chamar a API
 * externa (nada de rede nestes testes - so o guard-clause em perguntar()).
 * O parsing de resposta/chamada HTTP real fica coberto indiretamente pelos
 * testes de integracao do ProcessoAnexoController (resumo-ia), que mockam
 * o GeminiService inteiro.
 */
class GeminiServiceTest {

    @Test
    void perguntarRetornaVazioQuandoDesligadoPorKillSwitch() {
        GeminiService service = new GeminiService("chave-qualquer", "gemini-2.0-flash", false);

        assertThat(service.perguntar("Ola")).isEqualTo(Optional.empty());
    }

    @Test
    void perguntarRetornaVazioQuandoChaveNaoConfigurada() {
        GeminiService service = new GeminiService("", "gemini-2.0-flash", true);

        assertThat(service.perguntar("Ola")).isEqualTo(Optional.empty());
    }

    @Test
    void perguntarRetornaVazioQuandoChaveEmBranco() {
        GeminiService service = new GeminiService("   ", "gemini-2.0-flash", true);

        assertThat(service.perguntar("Ola")).isEqualTo(Optional.empty());
    }

    @Test
    void isDisponivelFalsoQuandoDesligado() {
        GeminiService service = new GeminiService("chave", "gemini-2.0-flash", false);

        assertThat(service.isDisponivel()).isFalse();
    }

    @Test
    void isDisponivelFalsoQuandoSemChave() {
        GeminiService service = new GeminiService(null, "gemini-2.0-flash", true);

        assertThat(service.isDisponivel()).isFalse();
    }

    @Test
    void isDisponivelVerdadeiroQuandoLigadoEComChave() {
        GeminiService service = new GeminiService("chave-valida", "gemini-2.0-flash", true);

        assertThat(service.isDisponivel()).isTrue();
    }
}
