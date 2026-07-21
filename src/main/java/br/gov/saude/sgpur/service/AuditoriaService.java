package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.LogAuditoria;
import br.gov.saude.sgpur.repository.LogAuditoriaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * Registra acoes relevantes para auditoria. O usuario e obtido do contexto
 * de seguranca. Falhas de log nunca devem quebrar a acao principal.
 */
@Service
public class AuditoriaService {

    private final LogAuditoriaRepository repo;

    public AuditoriaService(LogAuditoriaRepository repo) {
        this.repo = repo;
    }

    public void registrar(String acao, String detalhe) {
        registrar(acao, detalhe, null);
    }

    /**
     * Registra a acao com o IP do cliente. Usar quando o IP for relevante para
     * nao-repudio (ex.: voto autenticado do Portal do Avaliador).
     */
    public void registrar(String acao, String detalhe, String ip) {
        try {
            String usuario = usuarioAtual();
            String det = (detalhe != null && detalhe.length() > 400) ? detalhe.substring(0, 400) : detalhe;
            repo.save(new LogAuditoria(usuario, acao, det, ip));
        } catch (Exception ignored) {
            // auditoria nunca pode interromper a operacao principal
        }
    }

    public Page<LogAuditoria> listar(Pageable pageable) {
        return repo.findAllByOrderByDataHoraDesc(pageable);
    }

    private String usuarioAtual() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return (auth != null && auth.getName() != null) ? auth.getName() : "sistema";
    }
}
