package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.*;
import br.gov.saude.sgpur.service.EtapaFluxo.Estado;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Monta, em tempo real, a lista de etapas do processo, sinalizando o que ja
 * foi concluido, qual e a etapa atual e o que ainda falta. Reflete o fluxo:
 * Recebimento -> Envio aos 3 medicos -> Respostas -> Decisao
 * -> (Oficio de indeferimento, se reprovado) -> Resposta ao solicitante.
 */
@Service
public class FluxoProcessoService {

    private final ProcessoService processoService;

    public FluxoProcessoService(ProcessoService processoService) {
        this.processoService = processoService;
    }

    public List<EtapaFluxo> montarEtapas(Processo p) {
        List<EtapaFluxo> etapas = new ArrayList<>();
        boolean anterioresConcluidas = true;
        // Processo ja finalizado (Deferido/Indeferido/Cancelado) nao deve
        // ficar "preso" numa etapa anterior por causa de uma exigencia de
        // anexo criada DEPOIS que o processo foi decidido (ex.: capa
        // automatica so passou a existir em processos recebidos a partir de
        // 2026-07-09; processos antigos ja encerrados nao tem esse anexo e
        // nao devem exibir progresso 0% por isso). Para processos ja
        // encerrados, a cascata de "anteriores concluidas" e ignorada.
        boolean finalizado = p.getStatus() != null && p.getStatus().isFinalizado();

        // 1. Recebimento da solicitacao: exige a copia da solicitacao ORIGINAL
        //    (manual) E a capa do processo (CAPA_PROCESSO, gerada pelo sistema
        //    com os dados do solicitante e os 3 medicos). A copia anonimizada
        //    para as equipes e gerada no passo 2 (Envio). Mantido em sincronia
        //    com ProcessoDetalheController.recebimentoFeito, que usa a mesma
        //    dupla condicao para liberar a aba de Envio.
        boolean temOriginal = temAnexo(p, TipoAnexo.SOLICITACAO_RECEBIDA);
        boolean temCapa = temAnexo(p, TipoAnexo.CAPA_PROCESSO);
        boolean recebimentoOk = temOriginal && temCapa;
        String detReceb;
        if (recebimentoOk) {
            detReceb = "Solicitacao original e capa do processo anexadas.";
        } else {
            List<String> faltasReceb = new ArrayList<>();
            if (!temOriginal) faltasReceb.add("copia da solicitacao original");
            if (!temCapa) faltasReceb.add("capa do processo");
            detReceb = "Falta: " + String.join(", ", faltasReceb) + ".";
        }
        etapas.add(montar("Recebimento da solicitacao", "inbox-fill",
            recebimentoOk, anterioresConcluidas, detReceb));
        anterioresConcluidas = finalizado || (anterioresConcluidas && recebimentoOk);

        // 2. Envio aos 3 medicos (data de envio registrada em todos os pareceres).
        //    Exige ao menos um documento clinico (PDF) anexado: o PDF dos
        //    avaliadores e montado SO com esses documentos (com cabecalho
        //    carimbado), sem folha-rosto gerada pelo sistema.
        int totalMedicos = p.getPareceres().size();
        long enviadosCount = p.getPareceres().stream().filter(par -> par.getDataEnvio() != null).count();
        boolean temDocClinicoPdf = p.getAnexos().stream()
            .anyMatch(a -> a.getTipo() == TipoAnexo.DOCUMENTO_CLINICO_AVALIADOR
                && a.getContentType() != null
                && a.getContentType().toLowerCase().contains("application/pdf"));
        boolean enviado = totalMedicos == ProcessoService.AVALIADORES_POR_PROCESSO
            && enviadosCount == totalMedicos;
        String detEnvio;
        if (totalMedicos != ProcessoService.AVALIADORES_POR_PROCESSO) {
            detEnvio = "Processo deve ter " + ProcessoService.AVALIADORES_POR_PROCESSO
                + " medicos (atual: " + totalMedicos + ").";
        } else if (!temDocClinicoPdf && !enviado) {
            detEnvio = "Anexe o(s) documento(s) clinico(s) (PDF) para gerar o processo dos avaliadores.";
        } else if (!enviado) {
            detEnvio = "Registre o envio aos medicos (faltam " + (totalMedicos - enviadosCount)
                + " de " + totalMedicos + ").";
        } else {
            detEnvio = "Enviado aos " + totalMedicos + " medicos.";
        }
        etapas.add(montar("Envio aos 3 medicos", "send-fill", enviado, anterioresConcluidas, detEnvio));
        anterioresConcluidas = finalizado || (anterioresConcluidas && enviado);

        // 3. Respostas dos medicos (cada resposta recebida precisa do anexo).
        //    Por MAIORIA SIMPLES (2 de 3), assim que ha 2 votos do mesmo tipo a
        //    etapa esta pronta: nao e preciso aguardar o 3o parecer para decidir.
        long respondidos = processoService.contarRespondidos(p);
        long favoraveis = processoService.contarFavoraveis(p);
        var recebidosSemAnexo = processoService.pareceresRecebidosSemAnexo(p);
        var sugestaoResp = processoService.sugerirDecisao(p);
        boolean maioria = sugestaoResp.isPresent();
        boolean todasRespondidas = totalMedicos > 0 && respondidos == totalMedicos;
        boolean respostasOk = (maioria || todasRespondidas) && recebidosSemAnexo.isEmpty();
        String detResp;
        if (totalMedicos == 0) {
            detResp = "Aguardando definicao dos medicos.";
        } else if (!recebidosSemAnexo.isEmpty()) {
            String nomes = recebidosSemAnexo.stream()
                .map(par -> par.getMembro().getNome())
                .collect(java.util.stream.Collectors.joining(", "));
            detResp = "Anexe a resposta de: " + nomes + ".";
        } else if (maioria) {
            detResp = "Maioria formada (" + sugestaoResp.get().getDescricao()
                + ") - pronto para decidir. Favoraveis: " + favoraveis + ".";
        } else if (!todasRespondidas) {
            detResp = "Faltam " + (totalMedicos - respondidos) + " de " + totalMedicos
                + " pareceres. Favoraveis ate agora: " + favoraveis + ".";
        } else {
            detResp = respondidos + " pareceres recebidos (com anexo). Favoraveis: " + favoraveis + ".";
        }
        etapas.add(montar("Respostas dos medicos", "chat-square-text-fill",
            respostasOk, anterioresConcluidas, detResp));
        anterioresConcluidas = finalizado || (anterioresConcluidas && respostasOk);

        // 3b. Informacao complementar (apenas enquanto um medico pediu mais dados).
        //     Funciona como uma PAUSA: bloqueia a decisao ate o solicitante
        //     responder e o operador retomar a analise.
        if (p.getStatus() == StatusProcesso.SOLICITA_INFORMACAO) {
            etapas.add(montar("Informacao complementar", "question-circle-fill",
                false, anterioresConcluidas,
                "Aguardando informacao complementar do solicitante. Envie o pedido, "
                + "anexe a resposta recebida e retome a analise para liberar a decisao."));
            // bloqueia tudo o que vem depois enquanto nao for retomado
            anterioresConcluidas = false;
        }

        // 4. Decisao final
        boolean decidido = p.getStatus().isFinalizado();
        String detDecisao;
        if (decidido) {
            detDecisao = "Processo " + p.getStatus().getDescricao() + ".";
        } else {
            var sugestao = processoService.sugerirDecisao(p);
            detDecisao = sugestao
                .map(s -> "Sugestao automatica: " + s.getDescricao()
                    + " (regra " + ProcessoService.FAVORAVEIS_PARA_DEFERIR + " de "
                    + ProcessoService.AVALIADORES_POR_PROCESSO + " favoraveis).")
                .orElse("Aguardando pareceres suficientes para decidir.");
        }
        etapas.add(montar("Decisao final", "scale", decidido, anterioresConcluidas, detDecisao));
        anterioresConcluidas = finalizado || (anterioresConcluidas && decidido);

        // 5. Oficio de indeferimento (apenas quando indeferido)
        if (p.getStatus() == StatusProcesso.INDEFERIDO) {
            boolean oficioOk = p.getMotivoIndeferimento() != null && !p.getMotivoIndeferimento().isBlank()
                && temAnexo(p, TipoAnexo.OFICIO_INDEFERIMENTO)
                && p.getDataEmissaoOficio() != null;
            List<String> faltas = new ArrayList<>();
            if (p.getMotivoIndeferimento() == null || p.getMotivoIndeferimento().isBlank())
                faltas.add("motivo da reprova");
            if (!temAnexo(p, TipoAnexo.OFICIO_INDEFERIMENTO)) faltas.add("anexo do oficio");
            if (p.getDataEmissaoOficio() == null) faltas.add("data de emissao");
            String detOficio = oficioOk ? "Oficio de indeferimento completo."
                : "Falta: " + String.join(", ", faltas) + ".";
            etapas.add(montar("Oficio de indeferimento", "file-earmark-text-fill",
                oficioOk, anterioresConcluidas, detOficio));
            anterioresConcluidas = anterioresConcluidas && oficioOk;
        }

        // 5b. Comprovante de insercao da urgencia renal no SNT (apenas quando deferido)
        if (p.getStatus() == StatusProcesso.DEFERIDO) {
            boolean comprovanteOk = temAnexo(p, TipoAnexo.COMPROVANTE_SNT);
            etapas.add(montar("Comprovante SNT", "clipboard2-check-fill",
                comprovanteOk, anterioresConcluidas,
                comprovanteOk ? "Comprovante de insercao da urgencia renal no SNT anexado."
                              : "Anexe o comprovante de insercao da urgencia renal no "
                                + "Sistema Nacional de Transplantes (SNT)."));
            anterioresConcluidas = anterioresConcluidas && comprovanteOk;
        }

        // 6. Resposta ao solicitante — exige o flag de e-mail enviado E o
        //    comprovante de envio (print/PDF do e-mail) anexado ao processo.
        boolean emailMarcado = p.isEmailEnviadoSolicitante();
        boolean temComprovanteEnvio = temAnexo(p, TipoAnexo.COMPROVANTE_ENVIO_SOLICITANTE);
        boolean respostaOk = emailMarcado && temComprovanteEnvio;
        String detResposta;
        if (respostaOk) {
            detResposta = "Resposta enviada ao solicitante e comprovante anexado.";
        } else {
            java.util.List<String> faltasResp = new java.util.ArrayList<>();
            if (!emailMarcado) faltasResp.add("marcar e-mail como enviado");
            if (!temComprovanteEnvio) faltasResp.add("anexar comprovante de envio (print/PDF do e-mail)");
            detResposta = "Falta: " + String.join(", ", faltasResp) + ".";
        }
        etapas.add(montar("Resposta ao solicitante", "envelope-check-fill",
            respostaOk, anterioresConcluidas, detResposta));

        return etapas;
    }

    /** Mensagem curta de "o que falta" para o processo (etapa atual pendente). */
    public String resumoPendencia(Processo p) {
        for (EtapaFluxo e : montarEtapas(p)) {
            if (e.estado() == Estado.ATUAL) {
                return e.titulo() + ": " + e.detalhe();
            }
        }
        return "Processo concluido.";
    }

    private EtapaFluxo montar(String titulo, String icone, boolean concluida,
                              boolean anterioresConcluidas, String detalhe) {
        // So mostra CONCLUIDA (verde) se as etapas anteriores tambem estiverem
        // concluidas - senao a etapa fica "verde fora de ordem" mesmo com sua
        // propria condicao satisfeita (ex.: resposta ao solicitante marcada
        // antes do comprovante SNT ser anexado). Timeline le como progressao
        // sequencial, entao a cor precisa respeitar essa ordem.
        Estado estado;
        if (concluida && anterioresConcluidas) {
            estado = Estado.CONCLUIDA;
        } else if (anterioresConcluidas) {
            estado = Estado.ATUAL;
        } else {
            estado = Estado.PENDENTE;
        }
        return new EtapaFluxo(titulo, icone, estado, detalhe);
    }

    private boolean temAnexo(Processo p, TipoAnexo tipo) {
        return p.getAnexos().stream().anyMatch(a -> a.getTipo() == tipo);
    }
}
