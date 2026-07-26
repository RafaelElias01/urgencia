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

    /**
     * Detecta se um {@link br.gov.saude.sgpur.domain.Processo} foi originado
     * do Portal do Solicitante (convertido a partir de uma
     * {@code SolicitacaoOnline}). Usado por {@code FluxoProcessoService} para
     * dispensar a exigencia de anexar manualmente a copia da solicitacao
     * original (TipoAnexo.SOLICITACAO_RECEBIDA) no Passo 1 - os dados ja
     * chegaram digitais pelo proprio sistema, nao existe "e-mail original".
     */
    boolean existsByProcessoGeradoId(Long processoId);

    /**
     * A {@code SolicitacaoOnline} que gerou o processo, se houver. Usado por
     * {@code ProcessoService#excluir} para desvincular o processo antes de
     * apagar - {@code SolicitacaoOnline.processoGerado} e um
     * {@code @ManyToOne} sem cascade/orphanRemoval configurado a partir do
     * {@code Processo}, entao um DELETE direto do processo estoura violacao
     * de FK enquanto essa linha ainda apontar pra ele.
     */
    Optional<SolicitacaoOnline> findByProcessoGeradoId(Long processoId);

    /**
     * Id da {@code SolicitacaoOnline} que gerou o processo, se houver. Usado
     * para linkar de volta a triagem original (ver
     * {@code SolicitacaoOnlineTriagemController}) na tela de detalhe do
     * processo.
     */
    @Query("""
        select s.id from SolicitacaoOnline s
        where s.processoGerado.id = :processoId
        """)
    Optional<Long> findIdByProcessoGeradoId(@Param("processoId") Long processoId);
}
