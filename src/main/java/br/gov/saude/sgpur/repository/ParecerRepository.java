package br.gov.saude.sgpur.repository;

import br.gov.saude.sgpur.domain.Parecer;
import br.gov.saude.sgpur.domain.ResultadoParecer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ParecerRepository extends JpaRepository<Parecer, Long> {

    /**
     * Pareceres efetivamente respondidos e com as duas datas preenchidas, base
     * para o calculo de tempo de resposta dos avaliadores (dias corridos entre
     * dataEnvio e dataResposta). O membro e EAGER, entao vem carregado (sem N+1).
     */
    @Query("select p from Parecer p where p.resultado is not null "
        + "and p.dataEnvio is not null and p.dataResposta is not null")
    List<Parecer> findRespondidosComDatas();

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

    // -------------------------------------------------------------------------
    // Variantes com fetch join do Processo, usadas pelo Portal do Avaliador
    // (AvaliadorController) desde a remocao do @Transactional de nivel de classe
    // (2026-07-29). Com spring.jpa.open-in-view=false e sem essa transacao de
    // classe, Parecer.processo (LAZY) vira um proxy inutilizavel assim que a
    // consulta original retorna - qualquer navegacao a getProcesso() fora de uma
    // transacao aberta (ex.: getStatus/getNumero/getPacienteNome) lancaria
    // LazyInitializationException. Estas consultas carregam o Processo na MESMA
    // query (join fetch), entao ele chega como objeto real, nao um proxy - sem
    // precisar de nenhuma transacao adicional no controller. Os metodos originais
    // acima permanecem intocados (mesma assinatura, mesmo comportamento) para nao
    // afetar outros chamadores/testes que dependam deles.
    // -------------------------------------------------------------------------

    /**
     * Mesmo criterio de {@link #findByMembroIdAndResultadoIsNullAndDataEnvioIsNotNull},
     * com o Processo associado carregado via fetch join.
     */
    @Query("select p from Parecer p left join fetch p.processo "
        + "where p.membro.id = :membroId and p.resultado is null and p.dataEnvio is not null")
    List<Parecer> findPendentesComProcesso(@Param("membroId") Long membroId);

    /**
     * Mesmo criterio de {@link #findByMembroIdAndResultadoIsNotNullOrderByDataRespostaDesc},
     * com o Processo associado carregado via fetch join.
     */
    @Query("select p from Parecer p left join fetch p.processo "
        + "where p.membro.id = :membroId and p.resultado is not null order by p.dataResposta desc")
    List<Parecer> findHistoricoComProcesso(@Param("membroId") Long membroId);

    /**
     * Mesmo criterio de {@link #findByProcessoIdAndMembroId}, com o Processo
     * associado carregado via fetch join - usado quando o chamador precisa
     * navegar parecer.getProcesso() (ex.: checar o status antes de liberar o
     * voto em resolverParecerPendente).
     */
    @Query("select p from Parecer p left join fetch p.processo "
        + "where p.processo.id = :processoId and p.membro.id = :membroId")
    Optional<Parecer> findByProcessoIdAndMembroIdComProcesso(
        @Param("processoId") Long processoId, @Param("membroId") Long membroId);
}
