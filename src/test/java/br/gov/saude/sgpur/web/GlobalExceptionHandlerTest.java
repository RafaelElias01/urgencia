package br.gov.saude.sgpur.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes unitarios do GlobalExceptionHandler (chamando os metodos
 * diretamente, sem subir um contexto web): cada excecao capturada deve
 * mapear para a view/flash/status correto, e ResponseStatusException deve
 * ser sempre relancada (nao capturada), preservando o status HTTP original
 * (403/404 do AvaliadorController, por exemplo).
 */
@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Mock
    private RedirectAttributes redirectAttributes;
    @Mock
    private Model model;
    @Mock
    private HttpServletResponse response;
    @Mock
    private HttpServletRequest request;

    @Test
    void handleResponseStatusRelancaAExcecaoSemCapturar() {
        ResponseStatusException ex = new ResponseStatusException(HttpStatus.FORBIDDEN, "sem permissao");

        assertThatThrownBy(() -> handler.handleResponseStatus(ex))
            .isSameAs(ex);
    }

    @Test
    void handleNotFoundRedirecionaParaProcessosComFlashDeErro() {
        when(request.getRequestURI()).thenReturn("/processos/99");
        when(request.getContextPath()).thenReturn("");
        IllegalArgumentException ex = new IllegalArgumentException("id 99 nao existe");

        String view = handler.handleNotFound(ex, request, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/processos");
        verify(redirectAttributes).addFlashAttribute("erro", "Registro nao encontrado: id 99 nao existe");
    }

    @Test
    void handleBusinessRuleRedirecionaParaProcessosComAMensagemOriginal() {
        when(request.getRequestURI()).thenReturn("/processos/1/decidir");
        when(request.getContextPath()).thenReturn("");
        IllegalStateException ex = new IllegalStateException("Decisao bloqueada: aguardando informacao complementar");

        String view = handler.handleBusinessRule(ex, request, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/processos");
        verify(redirectAttributes).addFlashAttribute("erro",
            "Decisao bloqueada: aguardando informacao complementar");
    }

    @Test
    void handleNotFoundRedirecionaParaSolicitanteQuandoRotaEDoPortalDoSolicitante() {
        when(request.getRequestURI()).thenReturn("/solicitante/99");
        when(request.getContextPath()).thenReturn("");
        IllegalArgumentException ex = new IllegalArgumentException("Solicitacao online nao encontrada: 99");

        String view = handler.handleNotFound(ex, request, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/solicitante");
    }

    @Test
    void handleBusinessRuleRedirecionaParaSolicitanteQuandoRotaEDoPortalDoSolicitante() {
        when(request.getRequestURI()).thenReturn("/solicitante/1/cancelar");
        when(request.getContextPath()).thenReturn("");
        IllegalStateException ex = new IllegalStateException("Voce so pode cancelar as suas proprias solicitacoes.");

        String view = handler.handleBusinessRule(ex, request, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/solicitante");
    }

    @Test
    void handleUploadTooLargeRedirecionaComFlashAmigavel() {
        when(request.getRequestURI()).thenReturn("/processos/1/anexos");
        when(request.getContextPath()).thenReturn("");
        MaxUploadSizeExceededException ex = new MaxUploadSizeExceededException(10_000_000L);

        String view = handler.handleUploadTooLarge(ex, request, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/processos");
        verify(redirectAttributes).addFlashAttribute("erro",
            "Arquivo(s) excedem o limite de upload permitido. Reduza o tamanho e tente novamente.");
    }

    @Test
    void handleIOExceptionRetornaViewDeErroComStatus500() throws IOException {
        IOException ex = new IOException("disco cheio");

        String view = handler.handleIOException(ex, model);

        assertThat(view).isEqualTo("error");
        verify(model).addAttribute("status", HttpStatus.INTERNAL_SERVER_ERROR.value());
        verify(model).addAttribute("error", "Erro ao processar arquivo");
        verify(model).addAttribute("message",
            "Ocorreu um erro ao processar o arquivo. Tente novamente ou contacte o suporte.");
    }

    @Test
    void handleNoResourceEnviaStatus404SemLancarErro() throws IOException {
        NoResourceFoundException ex = new NoResourceFoundException(HttpMethod.GET, "/favicon.ico");

        handler.handleNoResource(ex, response);

        verify(response).sendError(HttpServletResponse.SC_NOT_FOUND);
    }

    @Test
    void handleGenericRetornaViewDeErroComStatus500() {
        Exception ex = new RuntimeException("falha inesperada");

        String view = handler.handleGeneric(ex, model);

        assertThat(view).isEqualTo("error");
        verify(model).addAttribute("status", HttpStatus.INTERNAL_SERVER_ERROR.value());
        verify(model).addAttribute("error", "Erro interno do servidor");
        verify(model).addAttribute("message",
            "Ocorreu um erro inesperado. Tente novamente ou contacte o suporte.");
    }
}
