package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.service.TempoRespostaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MembroControllerTest {

    @Mock
    MembroUrgenciaRenalRepository repo;
    @Mock
    ParecerRepository parecerRepo;
    @Mock
    TempoRespostaService tempoRespostaService;

    MembroController controller;

    @BeforeEach
    void setUp() {
        controller = new MembroController(repo, parecerRepo, tempoRespostaService);
    }

    private BindingResult semErros(Object alvo) {
        return new BeanPropertyBindingResult(alvo, "membro");
    }

    /**
     * So pode existir um coordenador CET-RS por vez (as regras de decisao por
     * maioria assumem no maximo um). Ao salvar um segundo membro marcado como
     * coordenador, o primeiro deve ser automaticamente desmarcado.
     */
    @Test
    void salvarNovoCoordenadorDesmarcaOAntigoAutomaticamente() {
        MembroUrgenciaRenal antigo = new MembroUrgenciaRenal("CET-RS", "Coordenador Antigo", null);
        antigo.setId(1L);
        antigo.setCoordenador(true);

        MembroUrgenciaRenal novo = new MembroUrgenciaRenal("CET-RS", "Coordenador Novo", null);
        novo.setId(2L);
        novo.setCoordenador(true);

        when(repo.findByCoordenadorTrue()).thenReturn(java.util.List.of(antigo));

        String view = controller.salvar(novo, semErros(novo), new RedirectAttributesModelMap());

        assertThat(view).isEqualTo("redirect:/membros");
        assertThat(antigo.isCoordenador()).isFalse();
        assertThat(novo.isCoordenador()).isTrue();
    }

    /** Salvar um membro sem marcar coordenador nao mexe em ninguem. */
    @Test
    void salvarMembroSemCoordenadorNaoAlteraOutros() {
        MembroUrgenciaRenal comum = new MembroUrgenciaRenal("HCPA", "Medico Comum", null);
        comum.setId(3L);
        comum.setCoordenador(false);

        String view = controller.salvar(comum, semErros(comum), new RedirectAttributesModelMap());

        assertThat(view).isEqualTo("redirect:/membros");
        org.mockito.Mockito.verify(repo, org.mockito.Mockito.never()).findByCoordenadorTrue();
    }
}
