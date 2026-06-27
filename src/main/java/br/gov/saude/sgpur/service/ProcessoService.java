package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.*;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.ProcessoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.Year;
import java.util.List;
import java.util.Optional;

@Service
public class ProcessoService {

    /** A partir deste ano a numeracao passa a ser automatica (2026 e manual). */
    private static final int ANO_NUMERACAO_AUTOMATICA = 2027;

    /** Cada processo e enviado a exatamente 3 medicos avaliadores. */
    public static final int AVALIADORES_POR_PROCESSO = 3;

    /** Quantidade de pareceres favoraveis necessaria para deferir. */
    public static final int FAVORAVEIS_PARA_DEFERIR = 2;

    private final ProcessoRepository processoRepository;
    private final MembroUrgenciaRenalRepository membroRepository;

    public ProcessoService(ProcessoRepository processoRepository,
                           MembroUrgenciaRenalRepository membroRepository) {
        this.processoRepository = processoRepository;
        this.membroRepository = membroRepository;
    }

    public List<Processo> listarTodos() {
        return processoRepository.findAllByOrderByAnoDescSequencialDesc();
    }

    public Processo buscar(Long id) {
        return processoRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Processo nao encontrado: " + id));
    }

    /** Numeracao automatica a partir de 2027; nos anos anteriores e manual. */
    public boolean isNumeracaoAutomatica(int ano) {
        return ano >= ANO_NUMERACAO_AUTOMATICA;
    }

    /** Proximo numero NN/AAAA para um ano (quando automatico). */
    public String proximoNumero(int ano) {
        Integer max = processoRepository.findMaxSequencialByAno(ano);
        int seq = (max == null ? 0 : max) + 1;
        return String.format("%02d/%d", seq, ano);
    }

    /**
     * Salva um novo processo. Gera o numero automaticamente quando o ano
     * estiver no regime automatico; caso contrario usa o numero informado.
     * Cria um parecer pendente para cada um dos 3 medicos escolhidos.
     */
    @Transactional
    public Processo cadastrar(Processo processo, List<Long> medicoIds) {
        if (medicoIds == null || medicoIds.size() != AVALIADORES_POR_PROCESSO) {
            throw new IllegalArgumentException(
                "Selecione exatamente " + AVALIADORES_POR_PROCESSO + " medicos avaliadores.");
        }
        int ano = processo.getDataSituacaoEspecial() != null
            ? processo.getDataSituacaoEspecial().getYear()
            : Year.now().getValue();
        processo.setAno(ano);

        if (isNumeracaoAutomatica(ano)) {
            processo.setNumero(proximoNumero(ano));
        }
        processo.setSequencial(extrairSequencial(processo.getNumero(), ano));

        // cria um parecer pendente para cada medico escolhido
        for (Long medicoId : medicoIds) {
            MembroUrgenciaRenal medico = membroRepository.findById(medicoId)
                .orElseThrow(() -> new IllegalArgumentException("Medico nao encontrado: " + medicoId));
            processo.addParecer(new Parecer(medico));
        }
        return processoRepository.save(processo);
    }

    @Transactional
    public Processo salvar(Processo processo) {
        return processoRepository.save(processo);
    }

    public long contarFavoraveis(Processo processo) {
        return processo.getPareceres().stream()
            .filter(p -> p.getResultado() == ResultadoParecer.FAVORAVEL)
            .count();
    }

    public long contarRespondidos(Processo processo) {
        return processo.getPareceres().stream()
            .filter(p -> p.getResultado() != null)
            .count();
    }

    /**
     * Sugestao de decisao pela regra "2 de 3 favoraveis = Deferido".
     * - 2+ favoraveis -> DEFERIDO (mesmo antes do 3o responder).
     * - todos os 3 responderam e nao alcancou 2 favoraveis -> INDEFERIDO.
     * - caso contrario (faltam respostas) -> Optional vazio.
     */
    public Optional<StatusProcesso> sugerirDecisao(Processo processo) {
        long favoraveis = contarFavoraveis(processo);
        long respondidos = contarRespondidos(processo);
        int total = processo.getPareceres().size();

        if (favoraveis >= FAVORAVEIS_PARA_DEFERIR) {
            return Optional.of(StatusProcesso.DEFERIDO);
        }
        if (respondidos == total && total > 0) {
            return Optional.of(StatusProcesso.INDEFERIDO);
        }
        return Optional.empty();
    }

    /** Registra a decisao final manual do servidor. */
    @Transactional
    public Processo decidir(Long id, StatusProcesso decisao, String motivoIndeferimento) {
        Processo p = buscar(id);
        p.setStatus(decisao);
        p.setDataDecisao(LocalDateTime.now());
        if (decisao == StatusProcesso.INDEFERIDO) {
            p.setMotivoIndeferimento(motivoIndeferimento);
        }
        return processoRepository.save(p);
    }

    private int extrairSequencial(String numero, int ano) {
        if (numero == null || numero.isBlank()) {
            return 0;
        }
        String parte = numero.split("/")[0].trim();
        try {
            return Integer.parseInt(parte);
        } catch (NumberFormatException e) {
            // fallback: proximo da sequencia do ano
            Integer max = processoRepository.findMaxSequencialByAno(ano);
            return (max == null ? 0 : max) + 1;
        }
    }
}
