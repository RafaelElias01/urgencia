package br.gov.saude.sgpur.repository;

import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MembroUrgenciaRenalRepository extends JpaRepository<MembroUrgenciaRenal, Long> {

    List<MembroUrgenciaRenal> findByAtivoTrueOrderByInstituicaoAsc();

    long countByAtivoTrue();
}
