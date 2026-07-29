package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.*;
import br.gov.saude.sgpur.repository.SolicitacaoOnlineRepository;
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
    private final SolicitacaoOnlineRepository solicitacaoOnlineRepository;

    public FluxoProcessoService(ProcessoService processoService,
                                 SolicitacaoOnlineRepository solicitacaoOnlineRepository) {
        this.processoService = processoService;
        this.solicitacaoOnlineRepository = solicitacaoOnlineRepository;
    }

    /**
     * true se o processo foi originado do Portal do Solicitante (convertido a
     * partir de uma {@code SolicitacaoOnline}). Nesse caso o Passo 1
     * (Recebimento) dispensa a copia manual da solicitacao original
     * (TipoAnexo.SOLICITACAO_RECEBIDA) - os dados ja chegaram digitais pelo
     * proprio sistema, nao existe "e-mail original" pra anexar. Fonte unica
     * usada tanto por {@link #montarEtapas} quanto por {@link #calcularGating}
     * (mesmo espirito de nao deixar as duas conta divergirem, ver historico
     * de bugs de wizard/timeline dessincronizados).
     */
    public boolean veioDoPortal(Processo p) {
        return p.getId() != null && solicitacaoOnlineRepository.existsByProcessoGeradoId(p.getId());
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

        // 1. Recebimento da solicitacao: SEMPRE automatico e concluido. Desde
        //    2026-07-27, cada processo nasce obrigatoriamente de uma
        //    SolicitacaoOnline convertida pelo Portal do Solicitante
        //    (ProcessoDetalheController.novo/salvar passaram a exigir
        //    origemSolicitacaoOnlineId) - nao existe mais cadastro manual "do
        //    zero" nem "e-mail original" a anexar, entao esta etapa nunca
        //    depende de nenhum anexo (SOLICITACAO_RECEBIDA/CAPA_PROCESSO).
        //    veioDoPortal(p) continua existindo so para achar o link "Ver
        //    solicitacao original" na tela de detalhe.
        String detReceb = "Recebimento automatico (solicitacao enviada pelo Portal do Solicitante).";
        etapas.add(montar("Recebimento da solicitacao", "inbox-fill",
            true, anterioresConcluidas, detReceb));
        anterioresConcluidas = finalizado || anterioresConcluidas;

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
        etapas.add(montar("Decisao final", "hammer", decidido, anterioresConcluidas, detDecisao));
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

        // 6. Resposta ao solicitante — exige o flag de e-mail enviado.
        //    O COMPROVANTE_ENVIO_SOLICITANTE (print manual) deixou de ser
        //    exigido: o proprio sistema envia o e-mail e registra a auditoria.
        boolean emailMarcado = p.isEmailEnviadoSolicitante();
        boolean respostaOk = emailMarcado;
        String detResposta;
        if (respostaOk) {
            detResposta = "Resposta enviada ao solicitante.";
        } else {
            detResposta = "Falta marcar o e-mail como enviado.";
        }
        etapas.add(montar("Resposta ao solicitante", "envelope-check-fill",
            respostaOk, anterioresConcluidas, detResposta));

        return etapas;
    }

    /**
     * Agrupa as etapas de {@link #montarEtapas} nos 5 passos fixos do wizard
     * horizontal da tela de detalhe, aplicando a MESMA cascata sequencial da
     * timeline vertical (um passo so fica CONCLUIDA se os anteriores tambem
     * estiverem). Garante que as duas linhas do tempo nunca divirjam.
     */
    public java.util.List<PassoWizard> montarPassosWizard(Processo p) {
        List<EtapaFluxo> etapas = montarEtapas(p);

        java.util.List<PassoWizard> passos = new ArrayList<>();
        boolean anteriorConcluido = true;

        anteriorConcluido = adicionarPasso(passos, 1, "1. Recebimento", "pane-recebimento",
            etapaConcluida(etapas, "Recebimento da solicitacao"), anteriorConcluido,
            "Conclua o Recebimento (passo 1) primeiro.", "Recebimento");

        anteriorConcluido = adicionarPasso(passos, 2, "2. Envio", "pane-envio",
            etapaConcluida(etapas, "Envio aos 3 medicos"), anteriorConcluido,
            "Conclua o Recebimento (passo 1) primeiro.", "Envio aos avaliadores");

        anteriorConcluido = adicionarPasso(passos, 3, "3. Respostas", "pane-respostas",
            etapaConcluida(etapas, "Respostas dos medicos"), anteriorConcluido,
            "Registre o Envio aos avaliadores (passo 2) primeiro.", "Pareceres dos avaliadores");

        // "Informacao complementar" pausa o fluxo (ver montarEtapas) mesmo com
        // "Respostas dos medicos" ja CONCLUIDA (maioria formada antes do pedido
        // de informacao). Sem essa checagem, o wizard destrava o passo 4 nesse
        // caso enquanto a timeline vertical mantem "Decisao final" PENDENTE -
        // as duas linhas do tempo dessincronizam.
        boolean aguardandoInfo = etapas.stream()
            .anyMatch(e -> e.titulo().equals("Informacao complementar") && !e.isConcluida());
        if (aguardandoInfo) {
            anteriorConcluido = false;
        }

        anteriorConcluido = adicionarPasso(passos, 4, "4. Decisao", "pane-decisao",
            etapaConcluida(etapas, "Decisao final"), anteriorConcluido,
            aguardandoInfo
                ? "Aguardando informacao complementar do solicitante antes de decidir."
                : "Receba todos os pareceres (passo 3) antes de decidir.",
            "Decisao final");

        // Passo 5 (Finalizacao) agrupa o bloco pos-decisao: Oficio (se
        // indeferido) ou Comprovante SNT (se deferido), mais a Resposta ao
        // solicitante. So concluido se TODAS as etapas desse bloco que
        // existirem para o status atual estiverem CONCLUIDA.
        boolean finalizacaoOk = etapaConcluidaSeExistir(etapas, "Oficio de indeferimento")
            && etapaConcluidaSeExistir(etapas, "Comprovante SNT")
            && etapaConcluida(etapas, "Resposta ao solicitante");
        adicionarPasso(passos, 5, "5. Finalizacao", "pane-finalizacao",
            finalizacaoOk, anteriorConcluido,
            "Registre a decisao (passo 4) primeiro.", "Finalizacao");

        return passos;
    }

    private boolean adicionarPasso(java.util.List<PassoWizard> passos, int numero, String titulo,
                                    String paneId, boolean concluido, boolean anteriorConcluido,
                                    String tooltipBloqueado, String tooltipLivre) {
        PassoWizard.Estado estado;
        String tooltip;
        if (concluido && anteriorConcluido) {
            estado = PassoWizard.Estado.CONCLUIDA;
            tooltip = tooltipLivre;
        } else if (anteriorConcluido) {
            estado = PassoWizard.Estado.ATUAL;
            tooltip = tooltipLivre;
        } else {
            estado = PassoWizard.Estado.BLOQUEADA;
            tooltip = tooltipBloqueado;
        }
        passos.add(new PassoWizard(numero, titulo, paneId, estado, tooltip));
        return anteriorConcluido && concluido;
    }

    /** true se a etapa com esse titulo existe e esta CONCLUIDA. */
    private boolean etapaConcluida(List<EtapaFluxo> etapas, String titulo) {
        return etapas.stream().anyMatch(e -> e.titulo().equals(titulo) && e.isConcluida());
    }

    /** true se a etapa nao existir para o status atual (nao se aplica) OU estiver CONCLUIDA. */
    private boolean etapaConcluidaSeExistir(List<EtapaFluxo> etapas, String titulo) {
        return etapas.stream().filter(e -> e.titulo().equals(titulo)).findFirst()
            .map(EtapaFluxo::isConcluida)
            .orElse(true);
    }

    /**
     * Gating das abas do wizard (1..5) na tela de detalhe: ate qual passo o
     * operador pode navegar/agir. Extraido de
     * {@code ProcessoDetalheController.detalhe} (vistoria arquitetural
     * 2026-07-25) para ficar num lugar testavel/reaproveitavel, em vez de
     * calculado inline no controller. Mesma logica de antes, sem mudanca de
     * comportamento.
     */
    public record GatingAbas(boolean liberadoRecebimento, boolean liberadoEnvio,
                              boolean liberadoRespostas, boolean liberadoDecisao,
                              boolean liberadoFinalizacao) {
    }

    public GatingAbas calcularGating(Processo p) {
        // 1 Recebimento sempre automatico e liberado; cada passo seguinte
        // exige o anterior pronto. Todo processo nasce do Portal do
        // Solicitante (ver veioDoPortal/montarEtapas), entao nao ha mais
        // nenhum anexo exigido para esta etapa.
        boolean recebimentoFeito = true;
        boolean envioFeito = envioRegistrado(p);
        long respondidos = processoService.contarRespondidos(p);
        int totalMedicos = p.getPareceres().size();
        boolean todasRespondidas = totalMedicos > 0 && respondidos == totalMedicos;
        // Maioria simples (2 de 3): assim que ha >=2 favoraveis OU >=2 desfavoraveis
        // o resultado ja esta definido e nao e preciso aguardar o 3o parecer.
        boolean maioriaFormada = processoService.sugerirDecisao(p).isPresent();
        boolean semAnexoPendente = processoService.pareceresRecebidosSemAnexo(p).isEmpty();
        // A decisao libera quando: (a) maioria ja formada, OU (b) todas as
        // respostas chegaram. Em ambos os casos os pareceres recebidos precisam
        // dos seus anexos (decidir exige RESPOSTA_AVALIADOR de todo parecer recebido).
        boolean respostasOk = (maioriaFormada || todasRespondidas) && semAnexoPendente;
        boolean decidido = p.getStatus().isFinalizado();
        // PAUSA: enquanto aguarda informacao complementar do solicitante, a
        // decisao e a finalizacao ficam bloqueadas ate o operador retomar a analise.
        boolean aguardandoInfo = p.getStatus() == StatusProcesso.SOLICITA_INFORMACAO;

        boolean liberadoRecebimento = true;
        boolean liberadoEnvio = recebimentoFeito;
        boolean liberadoRespostas = recebimentoFeito && envioFeito;
        boolean liberadoDecisao = liberadoRespostas && respostasOk && !aguardandoInfo;
        boolean liberadoFinalizacao = decidido;
        return new GatingAbas(liberadoRecebimento, liberadoEnvio, liberadoRespostas,
            liberadoDecisao, liberadoFinalizacao);
    }

    /**
     * Sub-rotulo dinamico ao lado do status na tela de detalhe (ex.: "Maioria
     * formada - pronto para decidir (Deferido)"). Extraido de
     * {@code ProcessoDetalheController.detalhe} (vistoria arquitetural
     * 2026-07-25). Por MAIORIA SIMPLES (2 de 3), assim que ha 2 votos do mesmo
     * tipo o resultado ja esta definido: nao mostra mais "Aguardando parecer",
     * e sim "pronto para decidir". So mostra "Aguardando parecer (x/total)"
     * quando ainda NAO ha maioria. Retorna {@code null} quando nao ha
     * sub-rotulo a mostrar (mesmo comportamento anterior).
     */
    public String calcularSubrotuloStatus(Processo p) {
        if (p.getStatus() != StatusProcesso.ENVIADO && p.getStatus() != StatusProcesso.EM_ANALISE) {
            return null;
        }
        boolean envioFeito = envioRegistrado(p);
        long respondidos = processoService.contarRespondidos(p);
        int totalMedicos = p.getPareceres().size();
        var sugestao = processoService.sugerirDecisao(p);
        boolean maioriaFormada = sugestao.isPresent();
        boolean semAnexoPendente = processoService.pareceresRecebidosSemAnexo(p).isEmpty();
        boolean todasRespondidas = totalMedicos > 0 && respondidos == totalMedicos;
        boolean respostasOk = (maioriaFormada || todasRespondidas) && semAnexoPendente;

        if (envioFeito && maioriaFormada) {
            return "Maioria formada - pronto para decidir (" + sugestao.get().getDescricao() + ")";
        } else if (envioFeito && totalMedicos > 0 && respondidos < totalMedicos) {
            return "Aguardando parecer (" + respondidos + "/" + totalMedicos + ")";
        } else if (envioFeito && respostasOk) {
            return "Pareceres recebidos - aguardando decisao";
        }
        return null;
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

    /**
     * Envio registrado = existem pareceres E TODOS tem dataEnvio preenchida.
     * Mesmo criterio usado em {@link #montarEtapas} (enviadosCount ==
     * totalMedicos) - fonte unica, para o gating das abas e o sub-rotulo nunca
     * divergirem da timeline (antes usavam apenas {@code pareceres.get(0)}, o
     * que destoava quando so parte dos pareceres tinha data de envio).
     */
    private boolean envioRegistrado(Processo p) {
        return !p.getPareceres().isEmpty()
            && p.getPareceres().stream().allMatch(par -> par.getDataEnvio() != null);
    }
}
