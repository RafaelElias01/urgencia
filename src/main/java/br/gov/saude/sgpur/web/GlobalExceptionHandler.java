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
     * Conflito de escrita concorrente: dois requests alteraram o MESMO
     * Processo (tem {@code @Version}) quase ao mesmo tempo — ex.: dois
     * avaliadores votando no mesmo processo, ou o operador editando enquanto
     * um avaliador vota. O Hibernate detecta e rejeita a segunda escrita
     * (comportamento correto — evita "o ultimo que salva ganha" silencioso),
     * mas sem este handler o usuario via um "Erro interno do servidor"
     * generico em vez de entender que so precisa tentar de novo.
     */
    @ExceptionHandler(org.springframework.orm.ObjectOptimisticLockingFailureException.class)
    public String handleOptimisticLock(
            org.springframework.orm.ObjectOptimisticLockingFailureException ex, RedirectAttributes ra) {
        log.warn("Conflito de escrita concorrente: {}", ex.getMessage());
        ra.addFlashAttribute("erro",
            "Este processo foi atualizado por outra pessoa enquanto voce editava. "
            + "Recarregue a pagina e tente novamente.");
        return "redirect:/processos";
    }

    /**
     * Violacao de constraint do banco: pode ser um dado duplicado (ex.:
     * numero de processo, unique) por dois operadores cadastrando quase ao
     * mesmo tempo, OU um campo maior que o limite da coluna (ex.: nome de
     * equipe/paciente muito longo) que escapou da validacao de formulario
     * (ex.: dado legado editado por outro caminho). A mensagem generica cobre
     * os dois casos sem presumir qual foi - "duplicado" seria enganoso
     * quando o problema real e tamanho do campo.
     */
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public String handleDataIntegrityViolation(
            org.springframework.dao.DataIntegrityViolationException ex, RedirectAttributes ra) {
        log.warn("Violacao de integridade de dados: {}", ex.getMessage());
        ra.addFlashAttribute("erro",
            "Nao foi possivel salvar: os dados informados violam uma regra do banco "
            + "(pode ser um valor duplicado, como o numero do processo, ou um campo "
            + "maior que o permitido). Revise os campos e tente novamente.");
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
