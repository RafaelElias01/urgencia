package br.gov.saude.sgpur.repository;

import br.gov.saude.sgpur.domain.SolicitacaoOnline;
import br.gov.saude.sgpur.domain.StatusSolicitacaoOnline;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SolicitacaoOnlineRepository extends JpaRepository<SolicitacaoOnline, Long> {

    List<SolicitacaoOnline> findByUsuarioSolicitanteIdOrderByDataEnvioDesc(Long usuarioSolicitanteId);

    List<SolicitacaoOnline> findByStatusOrderByDataEnvioAsc(StatusSolicitacaoOnline status);
}
