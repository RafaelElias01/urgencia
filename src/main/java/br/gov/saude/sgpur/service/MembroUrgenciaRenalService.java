package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Leituras de {@link MembroUrgenciaRenal} reutilizadas por controllers que
 * so precisam listar/consultar medicos avaliadores (para dropdowns de
 * formulario, contadores, etc.) - CRUD completo continua em
 * {@code MembroController}, que e o dono do dominio.
 */
@Service
public class MembroUrgenciaRenalService {

    private final MembroUrgenciaRenalRepository repository;

    public MembroUrgenciaRenalService(MembroUrgenciaRenalRepository repository) {
        this.repository = repository;
    }

    public List<MembroUrgenciaRenal> listarAtivos() {
        return repository.findByAtivoTrueOrderByInstituicaoAsc();
    }

    public long contarAtivos() {
        return repository.countByAtivoTrue();
    }

    public MembroUrgenciaRenal buscar(Long id) {
        return repository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Membro nao encontrado: " + id));
    }
}
