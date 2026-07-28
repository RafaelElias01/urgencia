package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.MensagemSolicitacao;
import br.gov.saude.sgpur.domain.MensagemSolicitacao.RemetenteMensagem;
import br.gov.saude.sgpur.domain.SolicitacaoOnline;
import br.gov.saude.sgpur.repository.MensagemSolicitacaoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Service
public class MensagemSolicitacaoService {

    private final MensagemSolicitacaoRepository repository;

    public MensagemSolicitacaoService(MensagemSolicitacaoRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public MensagemSolicitacao enviar(SolicitacaoOnline solicitacao, String texto, RemetenteMensagem remetente, Long remetenteId) {
        MensagemSolicitacao msg = new MensagemSolicitacao();
        msg.setSolicitacaoOnline(solicitacao);
        msg.setTexto(texto);
        msg.setRemetente(remetente);
        msg.setRemetenteId(remetenteId);
        msg.setDataEnvio(LocalDateTime.now());
        msg.setLida(false);
        return repository.save(msg);
    }

    @Transactional(readOnly = true)
    public List<MensagemSolicitacao> listarPorSolicitacao(Long solicitacaoOnlineId) {
        return repository.findBySolicitacaoOnlineIdOrderByDataEnvioAsc(solicitacaoOnlineId);
    }

    @Transactional
    public void marcarComoLidas(Long solicitacaoOnlineId, RemetenteMensagem remetente, Long remetenteId) {
        List<MensagemSolicitacao> naoLidas = repository
            .findBySolicitacaoOnlineIdOrderByDataEnvioAsc(solicitacaoOnlineId)
            .stream()
            .filter(m -> !m.isLida() && m.getRemetente() == remetente && !m.getRemetenteId().equals(remetenteId))
            .toList();
        for (MensagemSolicitacao m : naoLidas) {
            m.setLida(true);
        }
        if (!naoLidas.isEmpty()) {
            repository.saveAll(naoLidas);
        }
    }

    @Transactional(readOnly = true)
    public long contarNaoLidasOperador() {
        return repository.countByLidaFalseAndRemetente(RemetenteMensagem.SOLICITANTE);
    }

    @Transactional(readOnly = true)
    public Set<Long> idsSolicitacoesComMsgNaoLidaSolicitante() {
        return repository.findDistinctSolicitacaoOnlineIdsByLidaFalseAndRemetente(RemetenteMensagem.SOLICITANTE);
    }

    @Transactional(readOnly = true)
    public long contarNaoLidasSolicitante(Long solicitacaoOnlineId, Long solicitanteUsuarioId) {
        return repository.countBySolicitacaoOnlineIdAndLidaFalseAndRemetenteAndRemetenteIdNot(
            solicitacaoOnlineId, RemetenteMensagem.OPERADOR, solicitanteUsuarioId);
    }

    @Transactional(readOnly = true)
    public long contarNaoLidasSolicitantePorSolicitacao(Long solicitacaoOnlineId, Long solicitanteUsuarioId) {
        return repository.countBySolicitacaoOnlineIdAndLidaFalseAndRemetenteAndRemetenteIdNot(
            solicitacaoOnlineId, RemetenteMensagem.OPERADOR, solicitanteUsuarioId);
    }

    @Transactional
    public void apagar(Long mensagemId, Long remetenteId, RemetenteMensagem remetente) {
        MensagemSolicitacao msg = repository.findById(mensagemId)
            .orElseThrow(() -> new IllegalArgumentException("Mensagem nao encontrada."));
        if (!msg.getRemetenteId().equals(remetenteId) || msg.getRemetente() != remetente) {
            throw new IllegalArgumentException("Voce nao pode apagar esta mensagem.");
        }
        if (msg.isDeletada()) {
            return;
        }
        msg.setDeletada(true);
        msg.setDeletadaEm(LocalDateTime.now());
        msg.setTexto(null);
        repository.save(msg);
    }

    @Transactional
    public void excluirPorSolicitacao(Long solicitacaoOnlineId) {
        repository.deleteBySolicitacaoOnlineId(solicitacaoOnlineId);
    }
}
