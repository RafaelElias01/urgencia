package br.gov.saude.sgpur.repository;

import br.gov.saude.sgpur.domain.ControleUrgencia;
import br.gov.saude.sgpur.domain.SituacaoUrgencia;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;

public interface ControleUrgenciaRepository extends JpaRepository<ControleUrgencia, Long> {

    List<ControleUrgencia> findByAtivoTrueOrderByDataVencimentoAsc();

    List<ControleUrgencia> findBySituacaoOrderByDataVencimentoAsc(SituacaoUrgencia situacao);

    long countBySituacao(SituacaoUrgencia situacao);

    /** Urgencias ativas que vencem ate a data informada. */
    @Query("SELECT c FROM ControleUrgencia c WHERE c.situacao = 'ATIVA' AND c.dataVencimento <= :ate")
    List<ControleUrgencia> findAVencerOuVencidas(LocalDate ate);

    /** Urgencias ativas (ATIVA ou RENOVADA) ordenadas por vencimento. */
    @Query("SELECT c FROM ControleUrgencia c WHERE c.ativo = true ORDER BY c.dataVencimento ASC")
    List<ControleUrgencia> findAllAtivasOrdenadas();
}
