package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.*;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.service.AnexoStorageService;
import br.gov.saude.sgpur.service.AuditoriaService;
import br.gov.saude.sgpur.service.ConflitoEquipeMatcher;
import br.gov.saude.sgpur.service.EmailTemplateService;
import br.gov.saude.sgpur.service.FluxoProcessoService;
import br.gov.saude.sgpur.service.GeminiService;
import br.gov.saude.sgpur.service.ProcessoService;
import br.gov.saude.sgpur.service.ProcessoValidator;
import br.gov.saude.sgpur.service.auditoria.LogAuditoria;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.time.LocalDate;
import java.time.Year;
import java.util.Optional;

/** Criacao, detalhe, edicao/exclusao e recebimento (passo 1) do processo. */
@Controller
@RequestMapping("/processos")
public class ProcessoDetalheController {

    private final ProcessoService processoService;
    private final FluxoProcessoService fluxoService;
    private final EmailTemplateService emailTemplateService;
    private final MembroUrgenciaRenalRepository membroRepository;
    private final AnexoStorageService anexoStorage;
    private final AuditoriaService auditoria;
    private final GeminiService geminiService;
    private final ConflitoEquipeMatcher conflitoEquipeMatcher;

    public ProcessoDetalheController(ProcessoService processoService,
                                     FluxoProcessoService fluxoService,
                                     EmailTemplateService emailTemplateService,
                                     MembroUrgenciaRenalRepository membroRepository,
                                     AnexoStorageService anexoStorage,
                                     AuditoriaService auditoria,
                                     GeminiService geminiService,
                                     ConflitoEquipeMatcher conflitoEquipeMatcher) {
        this.processoService = processoService;
        this.fluxoService = fluxoService;
        this.emailTemplateService = emailTemplateService;
        this.membroRepository = membroRepository;
        this.anexoStorage = anexoStorage;
        this.auditoria = auditoria;
        this.geminiService = geminiService;
        this.conflitoEquipeMatcher = conflitoEquipeMatcher;
    }

    /**
     * Status que o operador pode escolher como DECISAO final na tela de
     * detalhe. So as decisoes reais entram aqui - SOLICITADO/ENVIADO/
     * EM_ANALISE/SOLICITA_INFORMACAO sao estados de andamento, nao decisoes.
     */
    @ModelAttribute("decisaoValores")
    public StatusProcesso[] decisaoValores() {
        return new StatusProcesso[]{
            StatusProcesso.DEFERIDO, StatusProcesso.INDEFERIDO, StatusProcesso.CANCELADO
        };
    }

    @ModelAttribute("resultadoValores")
    public ResultadoParecer[] resultadoValores() {
        return ResultadoParecer.values();
    }

    @ModelAttribute("tipoAnexoValores")
    public TipoAnexo[] tipoAnexoValores() {
        return TipoAnexo.values();
    }

    /** Controla a exibicao dos botoes de assistencia por IA nas telas (so aparecem se a chave estiver configurada). */
    @ModelAttribute("iaDisponivel")
    public boolean iaDisponivel() {
        return geminiService.isDisponivel();
    }

    @GetMapping("/novo")
    public String novo(Model model) {
        Processo p = new Processo();
        p.setDataSituacaoEspecial(LocalDate.now());
        int ano = Year.now().getValue();
        boolean automatica = processoService.isNumeracaoAutomatica(ano);
        if (!automatica) {
            p.setNumero(processoService.proximoNumero(ano)); // sugestao editavel
        }
        model.addAttribute("processo", p);
        model.addAttribute("numeracaoAutomatica", automatica);
        model.addAttribute("medicos", membroRepository.findByAtivoTrueOrderByInstituicaoAsc());
        model.addAttribute("totalAvaliadores", ProcessoService.AVALIADORES_POR_PROCESSO);
        return "processos/form";
    }

    @PostMapping
    public String salvar(@Valid @ModelAttribute("processo") Processo processo,
                         BindingResult result,
                         @RequestParam(value = "medicoIds", required = false) java.util.List<Long> medicoIds,
                         Model model, RedirectAttributes ra) {
        int ano = processo.getDataSituacaoEspecial() != null
            ? processo.getDataSituacaoEspecial().getYear() : Year.now().getValue();
        boolean automatica = processoService.isNumeracaoAutomatica(ano);

        // Numero so e obrigatorio/validado quando a numeracao for manual
        if (!automatica) {
            String numero = processo.getNumero();
            if (numero == null || numero.isBlank()) {
                result.rejectValue("numero", "obrigatorio", "Informe o numero do processo (NN/AAAA).");
            } else if (!numero.matches("\\d{1,3}/\\d{4}")) {
                result.rejectValue("numero", "formato", "Use o formato NN/AAAA (ex.: 01/2026).");
            } else if (processoService.numeroJaExiste(numero)) {
                result.rejectValue("numero", "duplicado",
                    "Ja existe um processo com o numero " + numero + ".");
            }
        }
        if (medicoIds == null || medicoIds.size() != ProcessoService.AVALIADORES_POR_PROCESSO) {
            result.reject("medicos", "Selecione exatamente "
                + ProcessoService.AVALIADORES_POR_PROCESSO + " medicos avaliadores.");
        }
        if (result.hasErrors()) {
            model.addAttribute("numeracaoAutomatica", automatica);
            model.addAttribute("medicos", membroRepository.findByAtivoTrueOrderByInstituicaoAsc());
            model.addAttribute("totalAvaliadores", ProcessoService.AVALIADORES_POR_PROCESSO);
            return "processos/form";
        }
        Processo salvo = processoService.cadastrar(processo, medicoIds);
        auditoria.registrar("PROCESSO_CADASTRADO",
            "Processo " + salvo.getNumero() + " - " + salvo.getPacienteNome());
        ra.addFlashAttribute("msg", "Processo " + salvo.getNumero() + " cadastrado.");
        return "redirect:/processos/" + salvo.getId();
    }

    @GetMapping("/{id}")
    public String detalhe(@PathVariable Long id, Model model) {
        Processo p = processoService.buscar(id);
        model.addAttribute("processo", p);
        var etapas = fluxoService.montarEtapas(p);
        model.addAttribute("etapas", etapas);
        long concluidas = etapas.stream().filter(e -> e.estado().name().equals("CONCLUIDA")).count();
        model.addAttribute("etapasConcluidas", concluidas);
        model.addAttribute("etapasTotal", etapas.size());
        model.addAttribute("progresso", etapas.isEmpty() ? 0 : Math.round(concluidas * 100.0 / etapas.size()));
        Optional<StatusProcesso> sugestao = processoService.sugerirDecisao(p);
        model.addAttribute("sugestao", sugestao.orElse(null));
        model.addAttribute("favoraveis", processoService.contarFavoraveis(p));
        model.addAttribute("deferidoPeloCoordenador", processoService.deferidoPeloCoordenador(p));
        model.addAttribute("emails", emailTemplateService.gerar(p));
        // IDs dos pareceres que ja possuem e-mail de resposta anexado
        java.util.Set<Long> pareceresComResposta = p.getAnexos().stream()
            .filter(a -> a.getParecer() != null)
            .map(a -> a.getParecer().getId())
            .collect(java.util.stream.Collectors.toSet());
        model.addAttribute("pareceresComResposta", pareceresComResposta);
        // IDs dos pareceres votados diretamente pelo avaliador autenticado no portal.
        // Esses pareceres sao IMUTAVEIS pelo operador: o campo de resultado fica
        // bloqueado (disabled) e o anexo de resposta nao pode ser excluido nem substituido.
        java.util.Set<Long> pareceresPortal = p.getPareceres().stream()
            .filter(par -> par.getOrigem() == OrigemParecer.AVALIADOR_SISTEMA)
            .map(Parecer::getId)
            .collect(java.util.stream.Collectors.toSet());
        model.addAttribute("pareceresPortal", pareceresPortal);
        // Anexo do tipo SOLICITACAO_AVALIADOR = copia anonimizada para as equipes
        Optional<Anexo> solicitacaoPdf = p.getAnexos().stream()
            .filter(a -> a.getTipo() == TipoAnexo.SOLICITACAO_AVALIADOR)
            .findFirst();
        model.addAttribute("solicitacaoPdf", solicitacaoPdf.orElse(null));
        // Anexo da solicitacao ORIGINAL recebida (com nome completo)
        Optional<Anexo> solicitacaoOriginal = p.getAnexos().stream()
            .filter(a -> a.getTipo() == TipoAnexo.SOLICITACAO_RECEBIDA)
            .findFirst();
        model.addAttribute("solicitacaoOriginal", solicitacaoOriginal.orElse(null));
        // Documentos clinicos anonimizados que serao consolidados no PDF dos avaliadores
        java.util.List<Anexo> documentosClinicos = p.getAnexos().stream()
            .filter(a -> a.getTipo() == TipoAnexo.DOCUMENTO_CLINICO_AVALIADOR)
            .collect(java.util.stream.Collectors.toList());
        model.addAttribute("documentosClinicos", documentosClinicos);
        // Aviso (nao bloqueia): medicos possivelmente da mesma equipe/instituicao
        // do solicitante (casa sigla x nome por extenso x cidade, ignorando
        // acentos/maiusculas - ver ConflitoEquipeMatcher).
        String equipe = p.getSolicitanteEquipe();
        java.util.List<String> medicosMesmaEquipe = p.getPareceres().stream()
            .map(Parecer::getMembro)
            .filter(m -> conflitoEquipeMatcher.mesmaEquipe(m.getInstituicao(), equipe))
            .map(m -> m.getNome() + " (" + m.getInstituicao() + ")")
            .distinct()
            .collect(java.util.stream.Collectors.toList());
        model.addAttribute("medicosMesmaEquipe", medicosMesmaEquipe);

        // --- Gating das abas (passo 1..5) e sub-rotulo de status ---
        // Calcula ate qual passo o operador pode navegar. Um passo so libera
        // quando o passo anterior esta concluido (mesma logica do checklist).
        boolean recebimentoFeito = p.getAnexos().stream()
                .anyMatch(a -> a.getTipo() == TipoAnexo.SOLICITACAO_RECEBIDA)
            && p.getAnexos().stream()
                .anyMatch(a -> a.getTipo() == TipoAnexo.CAPA_PROCESSO);
        boolean envioFeito = !p.getPareceres().isEmpty()
            && p.getPareceres().get(0).getDataEnvio() != null;
        long respondidos = processoService.contarRespondidos(p);
        int totalMedicos = p.getPareceres().size();
        boolean todasRespondidas = totalMedicos > 0 && respondidos == totalMedicos;
        // Maioria simples (2 de 3): assim que ha >=2 favoraveis OU >=2 desfavoraveis
        // o resultado ja esta definido e nao e preciso aguardar o 3o parecer.
        // (sugestao ja foi calculada acima via processoService.sugerirDecisao)
        boolean maioriaFormada = sugestao.isPresent();
        boolean semAnexoPendente = processoService.pareceresRecebidosSemAnexo(p).isEmpty();
        // A decisao libera quando: (a) maioria ja formada, OU (b) todas as
        // respostas chegaram. Em ambos os casos os pareceres recebidos precisam
        // dos seus anexos (decidir exige RESPOSTA_AVALIADOR de todo parecer recebido).
        boolean respostasOk = (maioriaFormada || todasRespondidas) && semAnexoPendente;
        boolean decidido = p.getStatus().isFinalizado();
        // PAUSA: enquanto aguarda informacao complementar do solicitante, a
        // decisao e a finalizacao ficam bloqueadas ate o operador retomar a analise.
        boolean aguardandoInfo = p.getStatus() == StatusProcesso.SOLICITA_INFORMACAO;
        model.addAttribute("aguardandoInfo", aguardandoInfo);

        // passoLiberado[i] = true se a aba do passo (1..5) pode ser aberta.
        // 1 Recebimento sempre liberado; cada passo seguinte exige o anterior pronto.
        // Anexos da aba Finalizacao
        Optional<Anexo> oficioAnexo = p.getAnexos().stream()
            .filter(a -> a.getTipo() == TipoAnexo.OFICIO_INDEFERIMENTO)
            .findFirst();
        model.addAttribute("oficioAnexo", oficioAnexo.orElse(null));
        Optional<Anexo> comprovanteSnT = p.getAnexos().stream()
            .filter(a -> a.getTipo() == TipoAnexo.COMPROVANTE_SNT)
            .findFirst();
        model.addAttribute("comprovanteSnT", comprovanteSnT.orElse(null));
        Optional<Anexo> comprovanteEnvioSolicitante = p.getAnexos().stream()
            .filter(a -> a.getTipo() == TipoAnexo.COMPROVANTE_ENVIO_SOLICITANTE)
            .findFirst();
        model.addAttribute("comprovanteEnvioSolicitante", comprovanteEnvioSolicitante.orElse(null));
        Optional<Anexo> comprovanteEnvioAvaliadores = p.getAnexos().stream()
            .filter(a -> a.getTipo() == TipoAnexo.EMAIL_ENVIADO_AVALIADORES)
            .findFirst();
        model.addAttribute("comprovanteEnvioAvaliadores", comprovanteEnvioAvaliadores.orElse(null));

        boolean liberadoRecebimento = true;
        boolean liberadoEnvio = recebimentoFeito;
        boolean liberadoRespostas = recebimentoFeito && envioFeito;
        boolean liberadoDecisao = liberadoRespostas && respostasOk && !aguardandoInfo;
        boolean liberadoFinalizacao = decidido;
        model.addAttribute("liberadoRecebimento", liberadoRecebimento);
        model.addAttribute("liberadoEnvio", liberadoEnvio);
        model.addAttribute("liberadoRespostas", liberadoRespostas);
        model.addAttribute("liberadoDecisao", liberadoDecisao);
        model.addAttribute("liberadoFinalizacao", liberadoFinalizacao);

        // Sub-rotulo dinamico ao lado do status. Por MAIORIA SIMPLES (2 de 3),
        // assim que ha 2 votos do mesmo tipo o resultado ja esta definido: nao
        // mostra mais "Aguardando parecer", e sim "pronto para decidir". So
        // mostra "Aguardando parecer (x/total)" quando ainda NAO ha maioria.
        String statusSubrotulo = null;
        if (p.getStatus() == StatusProcesso.ENVIADO
                || p.getStatus() == StatusProcesso.EM_ANALISE) {
            if (envioFeito && maioriaFormada) {
                statusSubrotulo = "Maioria formada - pronto para decidir ("
                    + sugestao.get().getDescricao() + ")";
            } else if (envioFeito && totalMedicos > 0 && respondidos < totalMedicos) {
                statusSubrotulo = "Aguardando parecer (" + respondidos + "/" + totalMedicos + ")";
            } else if (envioFeito && respostasOk) {
                statusSubrotulo = "Pareceres recebidos - aguardando decisao";
            }
        }
        model.addAttribute("statusSubrotulo", statusSubrotulo);

        return "processos/detalhe";
    }

    @GetMapping("/{id}/editar")
    public String editar(@PathVariable Long id, Model model) {
        model.addAttribute("processo", processoService.buscar(id));
        return "processos/editar";
    }

    @PostMapping("/{id}/editar")
    public String atualizar(@PathVariable Long id,
                            @Valid @ModelAttribute("processo") Processo form,
                            BindingResult result, RedirectAttributes ra) {
        if (result.hasErrors()) {
            return "processos/editar";
        }
        if (bloqueadoPorEncerrado(processoService.buscar(id), ra)) {
            return "redirect:/processos/" + id;
        }
        processoService.atualizarDados(id, form);
        auditoria.registrar("PROCESSO_EDITADO", "Processo id " + id);
        ra.addFlashAttribute("msg", "Processo atualizado.");
        return "redirect:/processos/" + id;
    }

    /**
     * Reabre um processo encerrado (Deferido/Indeferido/Cancelado), voltando-o
     * para ENVIADO. Restrito ao ADMIN (imposto no SecurityConfig por
     * {@code POST /processos/*}/reabrir). O botao so aparece para ADMIN e quando
     * o processo esta finalizado.
     */
    @PostMapping("/{id}/reabrir")
    public String reabrir(@PathVariable Long id, RedirectAttributes ra) {
        Processo p = processoService.buscar(id);
        String numero = p.getNumero();
        try {
            processoService.reabrir(id);
            auditoria.registrar("PROCESSO_REABERTO", "Processo " + numero + " reaberto (voltou para Enviado)");
            ra.addFlashAttribute("msg", "Processo " + numero + " reaberto. Status voltou para Enviado.");
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("erro", e.getMessage());
        }
        return "redirect:/processos/" + id;
    }

    // Exclusao e um caminho unico e incondicional: acao auditada pelo aspect.
    // O detalhe grava o id do processo (o numero nao esta disponivel como
    // argumento do metodo).
    @LogAuditoria(acao = "PROCESSO_EXCLUIDO", detalhe = "'Processo id ' + #args[0]")
    @PostMapping("/{id}/excluir")
    public String excluir(@PathVariable Long id, RedirectAttributes ra) {
        Processo p = processoService.buscar(id);
        String numero = p.getNumero();
        processoService.excluir(id);
        anexoStorage.removerPastaProcesso(p);
        ra.addFlashAttribute("msg", "Processo " + numero + " excluido.");
        return "redirect:/processos";
    }

    /**
     * Etapa 1 (Recebimento da solicitacao): anexa a copia da solicitacao
     * ORIGINAL recebida e gera automaticamente a CAPA do processo (PDF com os
     * dados do solicitante e os medicos avaliadores), salva na pasta do
     * processo. A copia anonimizada para as equipes ("Processo CET-RS...") e
     * gerada no passo 2 (envio).
     */
    @PostMapping("/{id}/recebimento")
    public String registrarRecebimento(@PathVariable Long id,
                                       @RequestParam(value = "arquivo", required = false) MultipartFile arquivo,
                                       RedirectAttributes ra) {
        Processo p = processoService.buscar(id);
        if (bloqueadoPorEncerrado(p, ra)) {
            return "redirect:/processos/" + id + "#recebimento";
        }

        // 1) Copia da solicitacao original (com nome completo) — anexo manual.
        if (arquivo != null && !arquivo.isEmpty()) {
            try {
                anexoStorage.removerPorTipo(id, TipoAnexo.SOLICITACAO_RECEBIDA);
                anexoStorage.salvar(p, TipoAnexo.SOLICITACAO_RECEBIDA,
                    "Copia da solicitacao original recebida", arquivo);
                auditoria.registrar("ANEXO_ADICIONADO",
                    "Processo " + p.getNumero() + " - " + TipoAnexo.SOLICITACAO_RECEBIDA.getDescricao());
            } catch (IllegalArgumentException | IOException e) {
                ra.addFlashAttribute("erro", "Falha ao anexar a solicitacao original: " + e.getMessage());
                return "redirect:/processos/" + id + "#recebimento";
            }
        }

        ra.addFlashAttribute("msg", "Recebimento registrado: solicitacao original anexada.");
        return "redirect:/processos/" + id + "#recebimento";
    }

    /**
     * Guarda de edicao: se o processo esta encerrado, registra o flash de erro e
     * retorna true (o chamador deve redirecionar sem efetivar a alteracao). So o
     * ADMIN pode reabrir para voltar a alterar.
     */
    private boolean bloqueadoPorEncerrado(Processo p, RedirectAttributes ra) {
        if (processoService.edicaoBloqueada(p)) {
            ra.addFlashAttribute("erro", ProcessoValidator.MSG_ENCERRADO);
            return true;
        }
        return false;
    }
}
