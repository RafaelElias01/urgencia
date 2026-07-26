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
     *
     * Sem {@code distinct} de proposito: o fetch join da colecao multiplica as
     * linhas (1 por anexo), mas o Hibernate 6 ja deduplica a raiz sozinho, e o
     * {@code Optional} continua valido com N anexos (coberto por
     * {@code SolicitacaoOnlineDetalheIntegrationTest}). Um
     * {@code select distinct} aqui so adicionaria um DISTINCT sobre todas as
     * colunas das 4 entidades - custo inutil, e quebraria caso alguma delas
     * ganhe no futuro um tipo nao comparavel no Postgres (ex. @Lob/oid).
     */
    @Query("""
        select s from SolicitacaoOnline s
        left join fetch s.anexos
        left join fetch s.processoGerado
        left join fetch s.usuarioSolicitante
        where s.id = :id
        """)
    Optional<SolicitacaoOnline> findParaDetalhe(@Param("id") Long id);

    List<SolicitacaoOnline> findByUsuarioSolicitanteIdOrderByDataEnvioDesc(Long usuarioSolicitanteId);

    /**
     * Versao para a tela "Minhas solicitacoes", que agora precisa checar
     * {@code processoGerado.status} (para saber se aguarda informacao
     * complementar) sem estourar N+1 - o fetch join carrega o processo de
     * cada linha na mesma query. Mesmo raciocinio de {@link #findParaDetalhe}.
     */
    @Query("""
        select s from SolicitacaoOnline s
        left join fetch s.processoGerado
        where s.usuarioSolicitante.id = :usuarioId
        order by s.dataEnvio desc
        """)
    List<SolicitacaoOnline> findMinhasParaLista(@Param("usuarioId") Long usuarioId);

    List<SolicitacaoOnline> findByStatusOrderByDataEnvioAsc(StatusSolicitacaoOnline status);

    List<SolicitacaoOnline> findAllByOrderByDataEnvioDesc();

    long countByStatus(StatusSolicitacaoOnline status);
}
