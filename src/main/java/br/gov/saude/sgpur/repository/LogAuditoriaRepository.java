package br.gov.saude.sgpur.repository;

import br.gov.saude.sgpur.domain.LogAuditoria;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LogAuditoriaRepository extends JpaRepository<LogAuditoria, Long> {

    Page<LogAuditoria> findAllByOrderByDataHoraDesc(Pageable pageable);
}
