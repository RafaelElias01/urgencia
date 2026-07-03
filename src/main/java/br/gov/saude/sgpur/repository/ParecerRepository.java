package br.gov.saude.sgpur.repository;

import br.gov.saude.sgpur.domain.Parecer;
import br.gov.saude.sgpur.domain.ResultadoParecer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ParecerRepository extends JpaRepository<Parecer, Long> {

    /** Total de processos em que o membro foi designado avaliador. */
    long countByMembroId(Long membroId);

    /** Pareceres ja respondidos pelo membro. */
    long countByMembroIdAndResultadoNotNull(Long membroId);

    /** Pareceres do membro com um resultado especifico. */
    long countByMembroIdAndResultado(Long membroId, ResultadoParecer resultado);

    /**
     * Pareceres pendentes do membro (resultado nulo, envio ja registrado).
     * Usado pelo Portal do Avaliador para listar os processos que aguardam voto.
     */
    List<Parecer> findByMembroIdAndResultadoIsNullAndDataEnvioIsNotNull(Long membroId);

    /**
     * Pareceres ja votados pelo membro (resultado != null), do mais recente para
     * o mais antigo. Usado pelo historico do Portal do Avaliador.
     */
    List<Parecer> findByMembroIdAndResultadoIsNotNullOrderByDataRespostaDesc(Long membroId);

    /** Localiza o parecer de um membro especifico em um processo especifico. */
    Optional<Parecer> findByProcessoIdAndMembroId(Long processoId, Long membroId);

    /**
     * Pareceres pendentes (resultado nulo, envio ja registrado) de um processo
     * especifico. Usado para o lembrete manual de avaliacao pendente.
     */
    List<Parecer> findByProcessoIdAndResultadoIsNullAndDataEnvioIsNotNull(Long processoId);
}
