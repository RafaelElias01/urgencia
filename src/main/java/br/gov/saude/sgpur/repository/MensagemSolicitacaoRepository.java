package br.gov.saude.sgpur.repository;

import br.gov.saude.sgpur.domain.MensagemSolicitacao;
import br.gov.saude.sgpur.domain.MensagemSolicitacao.RemetenteMensagem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Set;

public interface MensagemSolicitacaoRepository extends JpaRepository<MensagemSolicitacao, Long> {

    List<MensagemSolicitacao> findBySolicitacaoOnlineIdOrderByDataEnvioAsc(Long solicitacaoOnlineId);

    long countBySolicitacaoOnlineIdAndLidaFalseAndRemetente(Long solicitacaoOnlineId, RemetenteMensagem remetente);

    long countByLidaFalseAndRemetente(RemetenteMensagem remetente);

    long countBySolicitacaoOnlineIdAndLidaFalseAndRemetenteAndRemetenteIdNot(Long solicitacaoOnlineId, RemetenteMensagem remetente, Long remetenteId);

    long countBySolicitacaoOnlineIdAndLidaFalse(Long solicitacaoOnlineId);

    long countByLidaFalseAndRemetenteAndRemetenteIdNot(RemetenteMensagem remetente, Long remetenteId);

    @Query("SELECT DISTINCT m.solicitacaoOnline.id FROM MensagemSolicitacao m WHERE m.lida = false AND m.remetente = :remetente")
    Set<Long> findDistinctSolicitacaoOnlineIdsByLidaFalseAndRemetente(@Param("remetente") RemetenteMensagem remetente);

    void deleteBySolicitacaoOnlineId(Long solicitacaoOnlineId);
}
