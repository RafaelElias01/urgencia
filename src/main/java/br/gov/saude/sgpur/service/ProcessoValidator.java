package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.*;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Centraliza as regras de negocio (contagem de votos e validacoes de decisao)
 * que antes viviam duplicadas entre {@link ProcessoService} e os controllers de
 * {@code /processos}. As funcoes sao puras (operam sobre o {@link Processo} em
 * memoria, sem acesso a banco) e as mensagens de erro sao as mesmas usadas na
 * camada web (flash {@code erro}) e no servico (excecoes) — mudar aqui muda nos
 * dois lugares.
 */
@Service
public class ProcessoValidator {

    /**
     * Mensagem exibida quando o operador tenta alterar um processo ENCERRADO
     * (Deferido/Indeferido/Cancelado). A edicao fica travada; so o ADMIN pode
     * reabrir (POST /processos/{id}/reabrir) para voltar a alterar.
     */
    public static final String MSG_ENCERRADO =
        "Processo encerrado: nenhuma alteracao e permitida. "
        + "Um administrador precisa reabrir o processo para altera-lo.";

    /**
     * True se o processo esta ENCERRADO e, portanto, com a edicao travada.
     * Usado como guarda nos endpoints de alteracao (etapas 1 a 4, upload
     * generico de anexos, exclusao de anexos e lembretes). As etapas 5 e 6
     * (oficio, comprovante SNT, resposta ao solicitante) e a leitura NAO usam
     * esta guarda — elas continuam liberadas apos a decisao.
     */
    public boolean edicaoBloqueada(Processo processo) {
        return processo.getStatus() != null && processo.getStatus().isFinalizado();
    }

    // ----- Contagem de votos (consultas puras) -----

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
        return temVotoCoordenadorFavoravel(processo)
            ? 1 : ProcessoService.FAVORAVEIS_PARA_DEFERIR;
    }

    /** Quantos pareceres desfavoraveis sao necessarios para Indeferir. */
    public long desfavoraveisNecessariosParaIndeferir() {
        return ProcessoService.DESFAVORAVEIS_PARA_INDEFERIR;
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

        if (favoraveis >= ProcessoService.FAVORAVEIS_PARA_DEFERIR) {
            return Optional.of(StatusProcesso.DEFERIDO);
        }
        if (naoFavoraveis >= ProcessoService.DESFAVORAVEIS_PARA_INDEFERIR) {
            return Optional.of(StatusProcesso.INDEFERIDO);
        }
        return Optional.empty();
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

    // ----- Validacoes de decisao (retornam a mensagem de erro, ou vazio) -----
    //
    // Sao granulares porque a camada web usa cada grupo para escolher a ancora
    // de redirecionamento (pausa/anexos -> #respostas; contagem -> topo). O
    // servico encadeia todas em validarDecisao (defesa em profundidade).

    /**
     * Bloqueio por PAUSA: aguardando informacao complementar do solicitante.
     * Excecao: o voto Favoravel do coordenador CET-RS defere sozinho e na hora,
     * mesmo com o processo pausado por causa do parecer de outro avaliador —
     * a pausa nao se aplica a essa regra (decisao de produto confirmada).
     */
    public Optional<String> validarPausaDecisao(Processo processo, StatusProcesso decisao) {
        if (processo.getStatus() == StatusProcesso.SOLICITA_INFORMACAO
                && (decisao == StatusProcesso.DEFERIDO || decisao == StatusProcesso.INDEFERIDO)
                && !temVotoCoordenadorFavoravel(processo)) {
            return Optional.of(
                "Processo aguardando informacao complementar do solicitante. "
                + "Registre o recebimento da informacao (retomar analise) antes de decidir.");
        }
        return Optional.empty();
    }

    /** Deferido exige votos favoraveis suficientes; Indeferido exige desfavoraveis suficientes. */
    public Optional<String> validarContagemVotos(Processo processo, StatusProcesso decisao) {
        long minFavoraveis = favoraveisNecessariosParaDeferir(processo);
        if (decisao == StatusProcesso.DEFERIDO && contarFavoraveis(processo) < minFavoraveis) {
            return Optional.of("Deferimento exige no minimo "
                + minFavoraveis + " parecer(es) favoravel(is).");
        }
        if (decisao == StatusProcesso.INDEFERIDO
                && contarNaoFavoraveis(processo) < ProcessoService.DESFAVORAVEIS_PARA_INDEFERIR) {
            return Optional.of("Indeferimento exige no minimo "
                + ProcessoService.DESFAVORAVEIS_PARA_INDEFERIR + " pareceres desfavoraveis.");
        }
        return Optional.empty();
    }

    /** Indeferido exige o motivo preenchido. */
    public Optional<String> validarMotivoIndeferimento(StatusProcesso decisao, String motivoIndeferimento) {
        if (decisao == StatusProcesso.INDEFERIDO
                && (motivoIndeferimento == null || motivoIndeferimento.isBlank())) {
            return Optional.of("Indeferimento exige o motivo.");
        }
        return Optional.empty();
    }

    /** Toda resposta de medico recebida precisa ter o anexo comprobatorio antes de decidir. */
    public Optional<String> validarAnexosResposta(Processo processo, StatusProcesso decisao) {
        if (decisao == StatusProcesso.DEFERIDO || decisao == StatusProcesso.INDEFERIDO) {
            List<Parecer> semAnexo = pareceresRecebidosSemAnexo(processo);
            if (!semAnexo.isEmpty()) {
                String nomes = semAnexo.stream()
                    .map(par -> par.getMembro().getNome())
                    .collect(java.util.stream.Collectors.joining(", "));
                return Optional.of(
                    "Anexe a resposta dos medicos antes de decidir. Sem anexo: " + nomes + ".");
            }
        }
        return Optional.empty();
    }

    /**
     * Todas as pre-condicoes da decisao final, na mesma ordem imposta pelo
     * servico: pausa -> contagem de votos -> motivo -> anexos. Retorna a
     * primeira mensagem de erro encontrada, ou vazio se pode decidir.
     */
    public Optional<String> validarDecisao(Processo processo, StatusProcesso decisao, String motivoIndeferimento) {
        return validarPausaDecisao(processo, decisao)
            .or(() -> validarContagemVotos(processo, decisao))
            .or(() -> validarMotivoIndeferimento(decisao, motivoIndeferimento))
            .or(() -> validarAnexosResposta(processo, decisao));
    }

    /**
     * Bloqueio da confirmacao da resposta ao solicitante: Deferido exige o
     * comprovante SNT anexado; Indeferido exige o oficio de indeferimento
     * (simetria entre as duas decisoes finais).
     */
    public Optional<String> validarRespostaSolicitante(Processo processo) {
        if (processo.getStatus() == StatusProcesso.DEFERIDO
                && processo.getAnexos().stream().noneMatch(a -> a.getTipo() == TipoAnexo.COMPROVANTE_SNT)) {
            return Optional.of(
                "Anexe o comprovante de insercao no SNT antes de confirmar a resposta ao solicitante.");
        }
        if (processo.getStatus() == StatusProcesso.INDEFERIDO
                && processo.getAnexos().stream().noneMatch(a -> a.getTipo() == TipoAnexo.OFICIO_INDEFERIMENTO)) {
            return Optional.of(
                "Anexe o oficio de indeferimento antes de confirmar a resposta ao solicitante.");
        }
        return Optional.empty();
    }
}
