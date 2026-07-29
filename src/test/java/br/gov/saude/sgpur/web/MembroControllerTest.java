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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

        MembroUrgenciaRenal gerenciado = new MembroUrgenciaRenal("CET-RS", "Coordenador Novo", null);
        gerenciado.setId(2L);
        gerenciado.setVersao(3L);

        MembroUrgenciaRenal form = new MembroUrgenciaRenal("CET-RS", "Coordenador Novo", null);
        form.setId(2L);
        form.setCoordenador(true);

        when(repo.findByCoordenadorTrue()).thenReturn(java.util.List.of(antigo));
        when(repo.findById(2L)).thenReturn(java.util.Optional.of(gerenciado));

        String view = controller.salvar(form, semErros(form), new RedirectAttributesModelMap());

        assertThat(view).isEqualTo("redirect:/membros");
        assertThat(antigo.isCoordenador()).isFalse();
        assertThat(gerenciado.isCoordenador()).isTrue();
    }

    /** Salvar um membro sem marcar coordenador nao mexe em ninguem. */
    @Test
    void salvarMembroSemCoordenadorNaoAlteraOutros() {
        MembroUrgenciaRenal gerenciado = new MembroUrgenciaRenal("HCPA", "Medico Comum", null);
        gerenciado.setId(3L);
        gerenciado.setVersao(0L);

        MembroUrgenciaRenal form = new MembroUrgenciaRenal("HCPA", "Medico Comum", null);
        form.setId(3L);
        form.setCoordenador(false);

        when(repo.findById(3L)).thenReturn(java.util.Optional.of(gerenciado));

        String view = controller.salvar(form, semErros(form), new RedirectAttributesModelMap());

        assertThat(view).isEqualTo("redirect:/membros");
        org.mockito.Mockito.verify(repo, org.mockito.Mockito.never()).findByCoordenadorTrue();
    }

    /**
     * Bug real de producao: o formulario manda o {@code id} mas NAO manda o
     * {@code @Version} — a entidade chega detached com {@code versao = null}
     * e o {@code save()} do Spring Data a trata como NOVA (persist), quebrando
     * toda edicao. A correcao carrega a entidade gerenciada por id e copia os
     * campos: o objeto salvo tem de ser o CARREGADO (com a versao real), nunca
     * o objeto do formulario.
     */
    @Test
    void salvarEdicaoPersisteAEntidadeCarregadaDoBancoNaoOObjetoDoFormulario() {
        MembroUrgenciaRenal gerenciado = new MembroUrgenciaRenal("HCPA", "Nome Antigo", "antigo@x.com");
        gerenciado.setId(7L);
        gerenciado.setVersao(5L);
        gerenciado.setAtivo(true);

        MembroUrgenciaRenal form = new MembroUrgenciaRenal("ISCMPA", "Nome Novo", "novo@x.com");
        form.setId(7L);
        form.setAtivo(false);

        when(repo.findById(7L)).thenReturn(java.util.Optional.of(gerenciado));

        controller.salvar(form, semErros(form), new RedirectAttributesModelMap());

        org.mockito.ArgumentCaptor<MembroUrgenciaRenal> captor =
            org.mockito.ArgumentCaptor.forClass(MembroUrgenciaRenal.class);
        org.mockito.Mockito.verify(repo).save(captor.capture());
        MembroUrgenciaRenal salvo = captor.getValue();

        assertThat(salvo).isSameAs(gerenciado);
        assertThat(salvo.getVersao()).isEqualTo(5L);
        assertThat(salvo.getNome()).isEqualTo("Nome Novo");
        assertThat(salvo.getInstituicao()).isEqualTo("ISCMPA");
        assertThat(salvo.getEmail()).isEqualTo("novo@x.com");
        assertThat(salvo.isAtivo()).isFalse();
    }

    /** Cadastro novo (sem id) nao consulta o banco — persiste o proprio form. */
    @Test
    void salvarNovoMembroUsaOProprioObjetoDoFormulario() {
        MembroUrgenciaRenal form = new MembroUrgenciaRenal("HCPA", "Medico Novo", null);

        controller.salvar(form, semErros(form), new RedirectAttributesModelMap());

        org.mockito.Mockito.verify(repo, org.mockito.Mockito.never()).findById(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(repo).save(form);
    }

    /** Editar um id inexistente responde 404, nao "erro interno do servidor". */
    @Test
    void salvarComIdInexistenteResponde404() {
        MembroUrgenciaRenal form = new MembroUrgenciaRenal("HCPA", "Fantasma", null);
        form.setId(999L);

        when(repo.findById(999L)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> controller.salvar(form, semErros(form), new RedirectAttributesModelMap()))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("statusCode", org.springframework.http.HttpStatus.NOT_FOUND);
    }

    /** A tela de edicao de um id inexistente tambem responde 404. */
    @Test
    void editarIdInexistenteResponde404() {
        when(repo.findById(999L)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> controller.editar(999L, new org.springframework.ui.ConcurrentModel()))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("statusCode", org.springframework.http.HttpStatus.NOT_FOUND);
    }
}
