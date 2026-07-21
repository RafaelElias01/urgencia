package br.gov.saude.sgpur.repository;

import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.StatusProcesso;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProcessoRepository extends JpaRepository<Processo, Long> {

    List<Processo> findAllByOrderByAnoDescSequencialDesc();

    /**
     * Carrega os processos ja com os pareceres e os respectivos membros
     * (fetch join) para montar a "planilha" do painel sem incorrer em N+1.
     */
    @Query("""
        select distinct p from Processo p
        left join fetch p.pareceres par
        left join fetch par.membro
        order by p.ano desc, p.sequencial desc
        """)
    List<Processo> findAllComPareceres();

    List<Processo> findByStatusOrderByAnoDescSequencialDesc(StatusProcesso status);

    /**
     * Processos encerrados (arquivo): recebe a colecao de status finais
     * (DEFERIDO/INDEFERIDO/CANCELADO), mais recentes primeiro.
     */
    List<Processo> findByStatusInOrderByAnoDescSequencialDesc(java.util.Collection<StatusProcesso> status);

    /** Anos distintos que possuem ao menos um processo, mais recente primeiro. */
    @Query("select distinct p.ano from Processo p order by p.ano desc")
    List<Integer> findAnosComProcessos();

    /**
     * Processos de um ano, ordenados por sequencial, ja com pareceres e medicos
     * (fetch join) para o relatorio anual sem incorrer em N+1.
     */
    @Query("""
        select distinct p from Processo p
        left join fetch p.pareceres par
        left join fetch par.membro
        where p.ano = :ano
        order by p.sequencial asc
        """)
    List<Processo> findByAnoComPareceres(@Param("ano") int ano);

    Optional<Processo> findByNumero(String numero);

    /** Maior sequencial ja usado em um ano (para gerar o proximo numero). */
    @Query("select max(p.sequencial) from Processo p where p.ano = :ano")
    Integer findMaxSequencialByAno(@Param("ano") int ano);

    long countByStatus(StatusProcesso status);

    @Query("""
        select p from Processo p
        where (:status is null or p.status = :status)
          and (:q is null or :q = ''
               or lower(p.pacienteNome) like lower(concat('%', :q, '%'))
               or p.numero like concat('%', :q, '%')
               or lower(p.solicitanteEquipe) like lower(concat('%', :q, '%')))
        order by
          case when p.status in (
            br.gov.saude.sgpur.domain.StatusProcesso.DEFERIDO,
            br.gov.saude.sgpur.domain.StatusProcesso.INDEFERIDO,
            br.gov.saude.sgpur.domain.StatusProcesso.CANCELADO) then 1 else 0 end asc,
          p.ano desc, p.sequencial desc
        """)
    Page<Processo> buscar(@Param("q") String q, @Param("status") StatusProcesso status, Pageable pageable);
}
