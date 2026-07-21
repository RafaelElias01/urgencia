package br.gov.saude.sgpur.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Tratamento global de excecoes para todas as pages/controllers.
 * Captura excecoes nao tratadas e redireciona para paginas de erro amigaveis,
 * evitando stacktraces expostas ao usuario.
 *
 * IMPORTANTE: ResponseStatusException (ex.: 403 do AvaliadorController) NAO
 * e capturada por este handler — o Spring a trata diretamente, preservando
 * o status HTTP original.
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Excecao com status HTTP definido (ex.: ResponseStatusException).
     * Deixa o Spring tratar normalmente — NAO captura para preservar o
     * status code original (403, 404, etc).
     */
    @ExceptionHandler(ResponseStatusException.class)
    public void handleResponseStatus(ResponseStatusException ex) {
        // Nao faz nada — deixa o Spring propagar o status HTTP correto
        throw ex;
    }

    /**
     * Entidade nao encontrada (id invalido, registro excluido).
     * Ex.: Processo.buscar(id) lança IllegalArgumentException.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public String handleNotFound(IllegalArgumentException ex, RedirectAttributes ra) {
        log.warn("Recurso nao encontrado: {}", ex.getMessage());
        ra.addFlashAttribute("erro", "Registro nao encontrado: " + ex.getMessage());
        return "redirect:/processos";
    }

    /**
     * Regra de negocio violada (ex.: tentar deferir sem votos suficientes).
     * Ex.: ProcessoService.decidir() lança IllegalStateException.
     */
    @ExceptionHandler(IllegalStateException.class)
    public String handleBusinessRule(IllegalStateException ex, RedirectAttributes ra) {
        log.warn("Regra de negocio violada: {}", ex.getMessage());
        ra.addFlashAttribute("erro", ex.getMessage());
        return "redirect:/processos";
    }

    /**
     * Falha de E/S (arquivo corrompido, permissao, disco cheio).
     * Ex.: AnexoStorageService, RelatorioService.
     */
    @ExceptionHandler(java.io.IOException.class)
    public String handleIOException(java.io.IOException ex, Model model) {
        log.error("Erro de E/S: {}", ex.getMessage(), ex);
        model.addAttribute("status", HttpStatus.INTERNAL_SERVER_ERROR.value());
        model.addAttribute("error", "Erro ao processar arquivo");
        model.addAttribute("message", "Ocorreu um erro ao processar o arquivo. Tente novamente ou contacte o suporte.");
        return "error";
    }

    /**
     * Recurso estatico nao encontrado (favicon.ico, etc).
     * Retorna 404 silencioso — sem log de ERROR.
     */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public void handleNoResource(org.springframework.web.servlet.resource.NoResourceFoundException ex,
                                  HttpServletResponse response) throws java.io.IOException {
        log.debug("Recurso estatico nao encontrado: {}", ex.getMessage());
        response.sendError(HttpServletResponse.SC_NOT_FOUND);
    }

    /**
     * Excecao generica nao mapeada (fallback).
     */
    @ExceptionHandler(Exception.class)
    public String handleGeneric(Exception ex, Model model) {
        log.error("Erro inesperado: {}", ex.getMessage(), ex);
        model.addAttribute("status", HttpStatus.INTERNAL_SERVER_ERROR.value());
        model.addAttribute("error", "Erro interno do servidor");
        model.addAttribute("message", "Ocorreu um erro inesperado. Tente novamente ou contacte o suporte.");
        return "error";
    }
}
