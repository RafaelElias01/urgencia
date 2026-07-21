package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.ControleUrgencia;
import br.gov.saude.sgpur.domain.SituacaoUrgencia;
import br.gov.saude.sgpur.repository.ControleUrgenciaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * Servico para o Controle de Urgencias para Transplante Renal.
 * <p>
 * Regra: a urgencia dura 30 dias. Se o receptor permanecer ativo na lista unica,
 * faz-se a renovacao por mais 30 dias, sem nova solicitacao da equipe.
 */
@Service
public class ControleUrgenciaService {

    /** Duracao padrao de cada ciclo de urgencia (30 dias). */
    public static final int DIAS_URGENCIA = 30;

    private final ControleUrgenciaRepository repo;

    public ControleUrgenciaService(ControleUrgenciaRepository repo) {
        this.repo = repo;
    }

    /**
     * Cria um novo registro de urgencia. A data de vencimento e calculada
     * automaticamente como {@code hoje + 30 dias}.
     */
    @Transactional
    public ControleUrgencia criar(ControleUrgencia c) {
        c.setSituacao(SituacaoUrgencia.ATIVA);
        if (c.getDataVencimento() == null) {
            c.setDataVencimento(LocalDate.now().plusDays(DIAS_URGENCIA));
        }
        return repo.save(c);
    }

    /**
     * Renova a urgencia por mais 30 dias.
     * <p>
     * A situacao passa para RENOVADA e o vencimento avanca {@code hoje + 30 dias}.
     */
    @Transactional
    public ControleUrgencia renovar(Long id) {
        ControleUrgencia c = repo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Registro nao encontrado: " + id));
        c.setSituacao(SituacaoUrgencia.RENOVADA);
        c.setDataUltimaRenovacao(LocalDate.now());
        c.setDataVencimento(LocalDate.now().plusDays(DIAS_URGENCIA));
        return repo.save(c);
    }

    /**
     * Cancela a urgencia (receptor nao esta mais ativo na lista unica).
     */
    @Transactional
    public ControleUrgencia cancelar(Long id, String observacoes) {
        ControleUrgencia c = repo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Registro nao encontrado: " + id));
        c.setSituacao(SituacaoUrgencia.CANCELADA);
        if (observacoes != null && !observacoes.isBlank()) {
            c.setObservacoes(observacoes);
        }
        return repo.save(c);
    }

    /**
     * Atualiza dados descritivos de um registro (nome, rgct, equipe, abo, observacoes).
     * Nao altera situacao nem vencimento.
     */
    @Transactional
    public ControleUrgencia atualizar(ControleUrgencia dados) {
        ControleUrgencia c = repo.findById(dados.getId())
            .orElseThrow(() -> new IllegalArgumentException("Registro nao encontrado: " + dados.getId()));
        c.setNomePaciente(dados.getNomePaciente());
        c.setRgct(dados.getRgct());
        c.setEquipe(dados.getEquipe());
        c.setAbo(dados.getAbo());
        c.setObservacoes(dados.getObservacoes());
        return repo.save(c);
    }

    /**
     * Retorna todos os registros ativos ordenados por vencimento (mais urgentes primeiro).
     */
    public List<ControleUrgencia> listarAtivas() {
        return repo.findAllAtivasOrdenadas();
    }

    /**
     * Retorna todos os registros (inclusive inativos).
     */
    public List<ControleUrgencia> listarTodas() {
        return repo.findAll();
    }

    /**
     * Retorna urgencias ativas cujo vencimento ja passou ou vence ate a data informada.
     */
    public List<ControleUrgencia> listarAVencerOuVencidas(LocalDate ate) {
        return repo.findAVencerOuVencidas(ate);
    }

    public ControleUrgencia buscarPorId(Long id) {
        return repo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Registro nao encontrado: " + id));
    }

    public long contarPorSituacao(SituacaoUrgencia situacao) {
        return repo.countBySituacao(situacao);
    }
}
