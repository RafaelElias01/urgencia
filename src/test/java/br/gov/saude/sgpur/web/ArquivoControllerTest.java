package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.StatusProcesso;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.repository.ProcessoRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Testes do ArquivoController: listagem read-only dos processos ENCERRADOS
 * (Deferido/Indeferido/Cancelado) e a busca em Java sobre esse conjunto.
 */
@WebMvcTest(ArquivoController.class)
class ArquivoControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean private ProcessoRepository processoRepository;
    // GlobalModelAdvice (@ControllerAdvice global) precisa dessas duas para o
    // contexto do @WebMvcTest subir, mesmo sem serem chamadas (usuario OPERADOR
    // nao tem ROLE_AVALIADOR, entao pendentesAvaliador() curto-circuita em 0).
    @MockitoBean private UsuarioRepository usuarioRepository;
    @MockitoBean private ParecerRepository parecerRepository;

    private static Processo processo(Long id, String numero, String paciente,
                                      String equipe, StatusProcesso status) {
        Processo p = new Processo();
        p.setId(id);
        p.setNumero(numero);
        p.setPacienteNome(paciente);
        p.setSolicitanteEquipe(equipe);
        p.setStatus(status);
        return p;
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void listaOsEncerradosDoRepositorio() throws Exception {
        Processo deferido = processo(1L, "01/2026", "Maria Silva", "Equipe A", StatusProcesso.DEFERIDO);
        when(processoRepository.findByStatusInOrderByAnoDescSequencialDesc(
            List.of(StatusProcesso.DEFERIDO, StatusProcesso.INDEFERIDO, StatusProcesso.CANCELADO)))
            .thenReturn(List.of(deferido));

        mvc.perform(get("/arquivo"))
            .andExpect(status().isOk())
            .andExpect(view().name("arquivo/lista"))
            .andExpect(model().attribute("processos", List.of(deferido)))
            .andExpect(model().attribute("q", (Object) null));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void semTermoDeBuscaRetornaTodosOsEncerrados() throws Exception {
        when(processoRepository.findByStatusInOrderByAnoDescSequencialDesc(any()))
            .thenReturn(List.of(
                processo(1L, "01/2026", "Maria Silva", "Equipe A", StatusProcesso.DEFERIDO),
                processo(2L, "02/2026", "Joao Souza", "Equipe B", StatusProcesso.INDEFERIDO)));

        mvc.perform(get("/arquivo"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("processos", org.hamcrest.Matchers.hasSize(2)));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void buscaPorNomeDoPacienteFiltraOsResultados() throws Exception {
        when(processoRepository.findByStatusInOrderByAnoDescSequencialDesc(any()))
            .thenReturn(List.of(
                processo(1L, "01/2026", "Maria Silva", "Equipe A", StatusProcesso.DEFERIDO),
                processo(2L, "02/2026", "Joao Souza", "Equipe B", StatusProcesso.INDEFERIDO)));

        mvc.perform(get("/arquivo").param("q", "maria"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("processos", org.hamcrest.Matchers.hasSize(1)))
            .andExpect(model().attribute("q", "maria"));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void buscaPorNumeroDoProcessoFiltraOsResultados() throws Exception {
        when(processoRepository.findByStatusInOrderByAnoDescSequencialDesc(any()))
            .thenReturn(List.of(
                processo(1L, "01/2026", "Maria Silva", "Equipe A", StatusProcesso.DEFERIDO),
                processo(2L, "02/2026", "Joao Souza", "Equipe B", StatusProcesso.INDEFERIDO)));

        mvc.perform(get("/arquivo").param("q", "02/2026"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("processos", org.hamcrest.Matchers.hasSize(1)));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void buscaPorEquipeSolicitanteFiltraOsResultados() throws Exception {
        when(processoRepository.findByStatusInOrderByAnoDescSequencialDesc(any()))
            .thenReturn(List.of(
                processo(1L, "01/2026", "Maria Silva", "Equipe A", StatusProcesso.DEFERIDO),
                processo(2L, "02/2026", "Joao Souza", "Equipe B", StatusProcesso.INDEFERIDO)));

        mvc.perform(get("/arquivo").param("q", "equipe b"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("processos", org.hamcrest.Matchers.hasSize(1)));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void termoQueNaoBateComNadaRetornaListaVazia() throws Exception {
        when(processoRepository.findByStatusInOrderByAnoDescSequencialDesc(any()))
            .thenReturn(List.of(
                processo(1L, "01/2026", "Maria Silva", "Equipe A", StatusProcesso.DEFERIDO)));

        mvc.perform(get("/arquivo").param("q", "nao existe ninguem assim"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("processos", org.hamcrest.Matchers.empty()));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void termoEmBrancoEhTratadoComoAusente() throws Exception {
        when(processoRepository.findByStatusInOrderByAnoDescSequencialDesc(any()))
            .thenReturn(List.of(
                processo(1L, "01/2026", "Maria Silva", "Equipe A", StatusProcesso.DEFERIDO)));

        mvc.perform(get("/arquivo").param("q", "   "))
            .andExpect(status().isOk())
            .andExpect(model().attribute("processos", org.hamcrest.Matchers.hasSize(1)));
    }
}
