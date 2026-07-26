package br.gov.saude.sgpur.repository;

import br.gov.saude.sgpur.domain.SolicitacaoOnline;
import br.gov.saude.sgpur.domain.StatusSolicitacaoOnline;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SolicitacaoOnlineRepository extends JpaRepository<SolicitacaoOnline, Long> {

    /**
     * Carrega a solicitacao com as associacoes que as telas de DETALHE
     * renderizam (anexos, processo gerado, usuario solicitante). Necessario
     * porque {@code spring.jpa.open-in-view} e {@code false} neste projeto: o
     * Thymeleaf renderiza DEPOIS do commit da transacao do controller, entao
     * qualquer proxy LAZY tocado no template estoura
     * {@code LazyInitializationException} (500). Ver
     * {@code SolicitacaoOnlineService#buscarParaDetalhe}.
     */
    @Query("""
        select distinct s from SolicitacaoOnline s
        left join fetch s.anexos
        left join fetch s.processoGerado
        left join fetch s.usuarioSolicitante
        where s.id = :id
        """)
    Optional<SolicitacaoOnline> findParaDetalhe(@Param("id") Long id);

    List<SolicitacaoOnline> findByUsuarioSolicitanteIdOrderByDataEnvioDesc(Long usuarioSolicitanteId);

    List<SolicitacaoOnline> findByStatusOrderByDataEnvioAsc(StatusSolicitacaoOnline status);

    List<SolicitacaoOnline> findAllByOrderByDataEnvioDesc();

    long countByStatus(StatusSolicitacaoOnline status);
}
