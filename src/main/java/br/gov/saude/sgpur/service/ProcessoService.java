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

    /** Quantidade de pareceres favoraveis necessaria para deferir (maioria simples). */
    public static final int FAVORAVEIS_PARA_DEFERIR = 2;

    /** Quantidade de pareceres desfavoraveis necessaria para indeferir (maioria simples). */
    public static final int DESFAVORAVEIS_PARA_INDEFERIR = 2;

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

    public org.springframework.data.domain.Page<Processo> buscar(
            String q, StatusProcesso status, org.springframework.data.domain.Pageable pageable) {
        String termo = (q == null || q.isBlank()) ? null : q.trim();
        return processoRepository.buscar(termo, status, pageable);
    }

    public Processo buscar(Long id) {
        return processoRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Processo nao encontrado: " + id));
    }

    /** Numeracao automatica a partir de 2027; nos anos anteriores e manual. */
    public boolean isNumeracaoAutomatica(int ano) {
        return ano >= ANO_NUMERACAO_AUTOMATICA;
    }

    public boolean numeroJaExiste(String numero) {
        return numero != null && processoRepository.findByNumero(numero).isPresent();
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

    /**
     * Marca o processo como ENVIADO aos avaliadores (etapa 5 do fluxo).
     * So altera o status quando ainda esta em uma fase anterior a decisao
     * (SOLICITADO / ENVIADO / EM_ANALISE / SOLICITA_INFORMACAO); nunca rebaixa
     * um processo ja decidido.
     */
    @Transactional
    public Processo registrarEnvio(Long id) {
        Processo p = buscar(id);
        if (p.getStatus().isEmAndamento()) {
            p.setStatus(StatusProcesso.ENVIADO);
        }
        return processoRepository.save(p);
    }

    /**
     * Recalcula o status "em andamento" do processo a partir dos pareceres
     * recebidos, SEM tomar a decisao final (que continua manual via decidir()):
     * - se algum medico pediu informacao e o processo ainda nao foi decidido,
     *   o status vai para SOLICITA_INFORMACAO;
     * - caso contrario permanece ENVIADO (ja foi enviado aos medicos).
     * Processos ja finalizados (DEFERIDO/INDEFERIDO/CANCELADO) nao sao tocados.
     */
    @Transactional
    public Processo atualizarStatusPorPareceres(Long id) {
        Processo p = buscar(id);
        if (p.getStatus().isFinalizado()) {
            return p;
        }
        boolean pediuInfo = p.getPareceres().stream()
            .anyMatch(par -> par.getResultado() == ResultadoParecer.SOLICITA_INFORMACAO);
        p.setStatus(pediuInfo ? StatusProcesso.SOLICITA_INFORMACAO : StatusProcesso.ENVIADO);
        return processoRepository.save(p);
    }

    /**
     * Tenta aplicar a decisao automatica por maioria simples (2 de 3), se todas as
     * pre-condicoes estiverem satisfeitas:
     *   - Processo ainda em andamento (nao finalizado) e nao aguardando info;
     *   - Maioria formada (>= 2 favoraveis ou >= 2 desfavoraveis);
     *   - Nenhum parecer recebido sem o anexo comprobatorio (RESPOSTA_AVALIADOR /
     *     ou origem AVALIADOR_SISTEMA que dispensa o anexo).
     * Se todas as condicoes estiverem ok, chama {@link #decidir} e retorna o
     * processo atualizado. Caso contrario retorna o processo sem alteracao.
     * Deve ser chamado apos {@link #atualizarStatusPorPareceres} e apos
     * {@link #retomarAposInformacao}.
     */
    @Transactional
    public Processo tentarDecisaoAutomatica(Long id) {
        Processo p = buscar(id);
        if (p.getStatus().isFinalizado()) {
            return p;
        }
        if (p.getStatus() == StatusProcesso.SOLICITA_INFORMACAO) {
            return p;
        }
        Optional<StatusProcesso> sugestao = sugerirDecisao(p);
        if (sugestao.isEmpty()) {
            return p;
        }
        // So decide automaticamente se nao ha pareceres recebidos sem anexo
        List<Parecer> semAnexo = pareceresRecebidosSemAnexo(p);
        if (!semAnexo.isEmpty()) {
            return p;
        }
        StatusProcesso decisao = sugestao.get();
        // Decisao automatica nao requer motivo de indeferimento (sera preenchido
        // manualmente na aba Finalizacao se necessario — mas exigimos o motivo
        // para gravar a decisao). Para INDEFERIDO automatico, deixamos em branco
        // e o operador completa depois; porem o servico decidir() nao valida o
        // motivo — so o controller valida. Aqui chamamos direto no servico.
        p.setStatus(decisao);
        p.setDataDecisao(java.time.LocalDateTime.now());
        return processoRepository.save(p);
    }

    /**
     * Retoma a analise apos a chegada da informacao complementar do solicitante:
     * tira o processo de SOLICITA_INFORMACAO e o devolve para ENVIADO (fluxo de
     * Respostas/Decisao), para que o(s) avaliador(es) concluam o voto. Limpa o
     * voto "Solicita informacao" dos pareceres que o usaram, para que o medico
     * registre o parecer definitivo (favoravel/nao favoravel). Nao toca em
     * processos ja finalizados.
     */
    @Transactional
    public Processo retomarAposInformacao(Long id) {
        Processo p = buscar(id);
        if (p.getStatus().isFinalizado()) {
            return p;
        }
        p.getPareceres().stream()
            .filter(par -> par.getResultado() == ResultadoParecer.SOLICITA_INFORMACAO)
            .forEach(par -> {
                // Reset COMPLETO para pendencia limpa: o parecer volta a ser uma
                // pendencia genuina, sem metadados obsoletos do voto "Solicita
                // informacao" antigo (preserva o nao-repudio do voto definitivo).
                // Mantem dataEnvio (o processo foi enviado de fato).
                par.setResultado(null);
                par.setDataResposta(null);
                par.setDataHoraVoto(null);
                par.setVotadoPor(null);
                par.setOrigem(null);
                par.setJustificativa(null);
            });
        p.setStatus(StatusProcesso.ENVIADO);
        return processoRepository.save(p);
    }

    /** True se algum avaliador pediu informacao complementar (parecer SOLICITA_INFORMACAO). */
    public boolean aguardandoInformacaoComplementar(Processo processo) {
        return processo.getStatus() == StatusProcesso.SOLICITA_INFORMACAO;
    }

    /** Atualiza apenas os dados descritivos do processo (numero e medicos nao mudam). */
    @Transactional
    public Processo atualizarDados(Long id, Processo form) {
        Processo p = buscar(id);
        p.setPacienteNome(form.getPacienteNome());
        p.setPacienteRgct(form.getPacienteRgct());
        p.setSolicitanteEquipe(form.getSolicitanteEquipe());
        p.setSolicitanteEmail(form.getSolicitanteEmail());
        p.setDataSituacaoEspecial(form.getDataSituacaoEspecial());
        p.setObservacoes(form.getObservacoes());
        return processoRepository.save(p);
    }

    @Transactional
    public void excluir(Long id) {
        processoRepository.delete(buscar(id));
    }

    public long contarFavoraveis(Processo processo) {
        return processo.getPareceres().stream()
            .filter(p -> p.getResultado() == ResultadoParecer.FAVORAVEL)
            .count();
    }

    public long contarNaoFavoraveis(Processo processo) {
        return processo.getPareceres().stream()
            .filter(p -> p.getResultado() == ResultadoParecer.NAO_FAVORAVEL)
            .count();
    }

    public long contarRespondidos(Processo processo) {
        return processo.getPareceres().stream()
            .filter(p -> p.getResultado() != null)
            .count();
    }

    /**
     * Pareceres ja "recebidos" (com resultado preenchido) que ainda NAO tem o
     * e-mail/documento de resposta anexado (TipoAnexo.RESPOSTA_AVALIADOR
     * vinculado ao proprio parecer). Regra: toda resposta de medico registrada
     * precisa ter o anexo comprobatorio antes de decidir o processo.
     */
    public List<Parecer> pareceresRecebidosSemAnexo(Processo processo) {
        java.util.Set<Long> comAnexo = processo.getAnexos().stream()
            .filter(a -> a.getTipo() == TipoAnexo.RESPOSTA_AVALIADOR && a.getParecer() != null)
            .map(a -> a.getParecer().getId())
            .collect(java.util.stream.Collectors.toSet());
        return processo.getPareceres().stream()
            .filter(par -> par.getResultado() != null)          // recebido
            // Pareceres votados diretamente pelo avaliador autenticado (AVALIADOR_SISTEMA)
            // nao exigem anexo: o proprio registro autenticado (usuario + IP + dataHoraVoto)
            // serve de comprovante. Origem null (legado) e OPERADOR_EMAIL continuam exigindo.
            .filter(par -> par.getOrigem() != OrigemParecer.AVALIADOR_SISTEMA)
            .filter(par -> !comAnexo.contains(par.getId()))     // sem anexo de resposta
            .toList();
    }

    /**
     * Sugestao de decisao:
     * - Se o coordenador CET-RS votou FAVORAVEL -> DEFERIDO (peso unico).
     * - Senao, maioria simples (2 de 3): 2+ favoraveis -> DEFERIDO;
     *   2+ desfavoraveis -> INDEFERIDO.
     * - Sem maioria ainda -> Optional vazio.
     */
    public Optional<StatusProcesso> sugerirDecisao(Processo processo) {
        if (temVotoCoordenadorFavoravel(processo)) {
            return Optional.of(StatusProcesso.DEFERIDO);
        }
        long favoraveis = contarFavoraveis(processo);
        long naoFavoraveis = contarNaoFavoraveis(processo);

        if (favoraveis >= FAVORAVEIS_PARA_DEFERIR) {
            return Optional.of(StatusProcesso.DEFERIDO);
        }
        if (naoFavoraveis >= DESFAVORAVEIS_PARA_INDEFERIR) {
            return Optional.of(StatusProcesso.INDEFERIDO);
        }
        return Optional.empty();
    }

    /** True se o coordenador CET-RS votou FAVORAVEL neste processo. */
    public boolean temVotoCoordenadorFavoravel(Processo processo) {
        return processo.getPareceres().stream()
            .anyMatch(p -> p.getResultado() == ResultadoParecer.FAVORAVEL
                && p.getMembro() != null && p.getMembro().isCoordenador());
    }

    /**
     * True se o processo foi deferido pelo voto do coordenador CET-RS
     * (status DEFERIDO + coordenador votou FAVORAVEL).
     */
    public boolean deferidoPeloCoordenador(Processo processo) {
        return processo.getStatus() == StatusProcesso.DEFERIDO
            && temVotoCoordenadorFavoravel(processo);
    }

    /** Quantos pareceres favoraveis sao necessarios para Deferir (considerando coordenador). */
    public long favoraveisNecessariosParaDeferir(Processo processo) {
        return temVotoCoordenadorFavoravel(processo) ? 1 : FAVORAVEIS_PARA_DEFERIR;
    }

    /** Quantos pareceres desfavoraveis sao necessarios para Indeferir. */
    public long desfavoraveisNecessariosParaIndeferir() {
        return DESFAVORAVEIS_PARA_INDEFERIR;
    }

    /** Registra a decisao final manual do servidor. */
    @Transactional
    public Processo decidir(Long id, StatusProcesso decisao, String motivoIndeferimento) {
        Processo p = buscar(id);
        // Regra: enquanto aguarda informacao complementar do solicitante, o
        // processo esta em PAUSA - nao pode ser deferido/indeferido. So Cancelado
        // pode encerra-lo (a qualquer momento). Retome a analise antes de decidir.
        if (p.getStatus() == StatusProcesso.SOLICITA_INFORMACAO
                && (decisao == StatusProcesso.DEFERIDO || decisao == StatusProcesso.INDEFERIDO)) {
            throw new IllegalStateException(
                "Processo aguardando informacao complementar do solicitante. "
                + "Registre o recebimento da informacao (retomar analise) antes de decidir.");
        }
        // Regra: Deferido exige votos suficientes (coordenador pode deferir sozinho ou 2/3).
        long minFavoraveis = temVotoCoordenadorFavoravel(p) ? 1 : FAVORAVEIS_PARA_DEFERIR;
        if (decisao == StatusProcesso.DEFERIDO
                && contarFavoraveis(p) < minFavoraveis) {
            throw new IllegalStateException("Deferimento exige no minimo "
                + minFavoraveis + " parecer(es) favoravel(is).");
        }
        if (decisao == StatusProcesso.INDEFERIDO
                && contarNaoFavoraveis(p) < DESFAVORAVEIS_PARA_INDEFERIR) {
            throw new IllegalStateException("Indeferimento exige no minimo "
                + DESFAVORAVEIS_PARA_INDEFERIR + " pareceres desfavoraveis.");
        }
        // Regra: Indeferido exige o motivo (o controller ja valida isso antes de
        // chamar este metodo, mas repetimos aqui em defesa - decidir() e publico
        // e nao deve confiar apenas na camada web para essa garantia).
        if (decisao == StatusProcesso.INDEFERIDO
                && (motivoIndeferimento == null || motivoIndeferimento.isBlank())) {
            throw new IllegalStateException("Indeferimento exige o motivo.");
        }
        // Regra: toda resposta de medico recebida precisa ter o anexo comprobatorio
        // antes de deferir ou indeferir (garante no minimo 2 anexos de resposta).
        if (decisao == StatusProcesso.DEFERIDO || decisao == StatusProcesso.INDEFERIDO) {
            List<Parecer> semAnexo = pareceresRecebidosSemAnexo(p);
            if (!semAnexo.isEmpty()) {
                String nomes = semAnexo.stream()
                    .map(par -> par.getMembro().getNome())
                    .collect(java.util.stream.Collectors.joining(", "));
                throw new IllegalStateException(
                    "Anexe a resposta dos medicos antes de decidir. Sem anexo: " + nomes + ".");
            }
        }
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
