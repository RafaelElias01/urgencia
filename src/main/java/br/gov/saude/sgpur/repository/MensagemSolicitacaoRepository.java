package br.gov.saude.sgpur.repository;

import br.gov.saude.sgpur.domain.MensagemSolicitacao;
import br.gov.saude.sgpur.domain.MensagemSolicitacao.RemetenteMensagem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MensagemSolicitacaoRepository extends JpaRepository<MensagemSolicitacao, Long> {

    List<MensagemSolicitacao> findBySolicitacaoOnlineIdOrderByDataEnvioAsc(Long solicitacaoOnlineId);

    long countBySolicitacaoOnlineIdAndLidaFalseAndRemetente(Long solicitacaoOnlineId, RemetenteMensagem remetente);

    long countByLidaFalseAndRemetente(RemetenteMensagem remetente);

    long countBySolicitacaoOnlineIdAndLidaFalseAndRemetenteAndRemetenteIdNot(Long solicitacaoOnlineId, RemetenteMensagem remetente, Long remetenteId);

    long countBySolicitacaoOnlineIdAndLidaFalse(Long solicitacaoOnlineId);

    long countByLidaFalseAndRemetenteAndRemetenteIdNot(RemetenteMensagem remetente, Long remetenteId);

    void deleteBySolicitacaoOnlineId(Long solicitacaoOnlineId);
}
