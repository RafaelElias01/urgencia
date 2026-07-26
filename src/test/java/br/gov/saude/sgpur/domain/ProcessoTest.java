package br.gov.saude.sgpur.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * getPareceres()/getAnexos() devolvem listas imutaveis: a unica forma
 * suportada de adicionar e addParecer()/addAnexo(), que tambem cuidam do
 * vinculo bidirecional (Parecer.processo / Anexo.processo). Sem essa
 * garantia, um .add() direto no getter deixaria o filho sem o processo
 * setado, quebrando a navegacao inversa usada em varios lugares do sistema.
 */
class ProcessoTest {

    @Test
    void getPareceresRetornaListaImutavel() {
        Processo p = new Processo();

        assertThatThrownBy(() -> p.getPareceres().add(new Parecer(new MembroUrgenciaRenal("HCPA", "Medico", null))))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void getAnexosRetornaListaImutavel() {
        Processo p = new Processo();

        assertThatThrownBy(() -> p.getAnexos().add(new Anexo()))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void addParecerVinculaOProcessoAoParecer() {
        Processo p = new Processo();
        Parecer parecer = new Parecer(new MembroUrgenciaRenal("HCPA", "Medico", null));

        p.addParecer(parecer);

        assertThat(p.getPareceres()).containsExactly(parecer);
        assertThat(parecer.getProcesso()).isSameAs(p);
    }

    @Test
    void addAnexoVinculaOProcessoAoAnexo() {
        Processo p = new Processo();
        Anexo anexo = new Anexo();

        p.addAnexo(anexo);

        assertThat(p.getAnexos()).containsExactly(anexo);
        assertThat(anexo.getProcesso()).isSameAs(p);
    }
}
