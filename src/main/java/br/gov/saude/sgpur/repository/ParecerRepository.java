package br.gov.saude.sgpur.repository;

import br.gov.saude.sgpur.domain.Parecer;
import br.gov.saude.sgpur.domain.ResultadoParecer;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ParecerRepository extends JpaRepository<Parecer, Long> {

    /** Total de processos em que o membro foi designado avaliador. */
    long countByMembroId(Long membroId);

    /** Pareceres ja respondidos pelo membro. */
    long countByMembroIdAndResultadoNotNull(Long membroId);

    /** Pareceres do membro com um resultado especifico. */
    long countByMembroIdAndResultado(Long membroId, ResultadoParecer resultado);
}
