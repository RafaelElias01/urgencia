package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.*;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.service.AnexoStorageService;
import br.gov.saude.sgpur.service.AuditoriaService;
import br.gov.saude.sgpur.service.DecisaoFinalService;
import br.gov.saude.sgpur.service.EmailTemplateService;
import br.gov.saude.sgpur.service.FluxoProcessoService;
import br.gov.saude.sgpur.service.OficioService;
import br.gov.saude.sgpur.service.ProcessoService;
import br.gov.saude.sgpur.service.RelatorioService;
import br.gov.saude.sgpur.service.SolicitacaoAvaliadorService;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.Year;
import java.util.Optional;

@Controller
@RequestMapping("/processos")
public class ProcessoController {

    private final ProcessoService processoService;
    private final FluxoProcessoService fluxoService;
    private final EmailTemplateService emailTemplateService;
    private final RelatorioService relatorioService;
    private final OficioService oficioService;
    private final SolicitacaoAvaliadorService solicitacaoAvaliadorService;
    private final MembroUrgenciaRenalRepository membroRepository;
    private final ParecerRepository parecerRepository;
    private final AnexoStorageService anexoStorage;
    private final AuditoriaService auditoria;
    private final DecisaoFinalService decisaoFinalService;

    public ProcessoController(ProcessoService processoService,
                              FluxoProcessoService fluxoService,
                              EmailTemplateService emailTemplateService,
                              RelatorioService relatorioService,
                              OficioService oficioService,
                              SolicitacaoAvaliadorService solicitacaoAvaliadorService,
                              MembroUrgenciaRenalRepository membroRepository,
                              ParecerRepository parecerRepository,
                              AnexoStorageService anexoStorage,
                              AuditoriaService auditoria,
                              DecisaoFinalService decisaoFinalService) {
        this.processoService = processoService;
        this.fluxoService = fluxoService;
        this.emailTemplateService = emailTemplateService;
        this.relatorioService = relatorioService;
        this.oficioService = oficioService;
        this.solicitacaoAvaliadorService = solicitacaoAvaliadorService;
        this.membroRepository = membroRepository;
        this.parecerRepository = parecerRepository;
        this.anexoStorage = anexoStorage;
        this.auditoria = auditoria;
        this.decisaoFinalService = decisaoFinalService;
    }

    @ModelAttribute("statusValores")
    public StatusProcesso[] statusValores() {
        return StatusProcesso.values();
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

    @GetMapping
    public String listar(@RequestParam(required = false) String q,
                         @RequestParam(required = false) StatusProcesso status,
                         @RequestParam(defaultValue = "0") int page,
                         Model model) {
        var pagina = processoService.buscar(q, status,
            org.springframework.data.domain.PageRequest.of(Math.max(page, 0), 15));
        var processos = pagina.getContent();
        model.addAttribute("processos", processos);
        model.addAttribute("paginaAtual", pagina.getNumber());
        model.addAttribute("totalPaginas", pagina.getTotalPages());
        model.addAttribute("q", q);
        model.addAttribute("statusSelecionado", status);
        // resumo de pendencia por processo (id -> texto)
        java.util.Map<Long, String> pendencias = new java.util.LinkedHashMap<>();
        for (Processo p : processos) {
            pendencias.put(p.getId(), fluxoService.resumoPendencia(p));
        }
        model.addAttribute("pendencias", pendencias);
        return "processos/lista";
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
        // Aviso (nao bloqueia): medicos da mesma equipe/instituicao do solicitante
        java.util.List<String> medicosMesmaEquipe = java.util.Collections.emptyList();
        String equipe = p.getSolicitanteEquipe();
        if (equipe != null && !equipe.isBlank()) {
            String alvo = equipe.trim().toLowerCase();
            medicosMesmaEquipe = p.getPareceres().stream()
                .map(par -> par.getMembro())
                .filter(m -> m.getInstituicao() != null && !m.getInstituicao().isBlank())
                .filter(m -> {
                    String inst = m.getInstituicao().trim().toLowerCase();
                    return inst.contains(alvo) || alvo.contains(inst);
                })
                .map(m -> m.getNome() + " (" + m.getInstituicao() + ")")
                .distinct()
                .collect(java.util.stream.Collectors.toList());
        }
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
        processoService.atualizarDados(id, form);
        auditoria.registrar("PROCESSO_EDITADO", "Processo id " + id);
        ra.addFlashAttribute("msg", "Processo atualizado.");
        return "redirect:/processos/" + id;
    }

    @PostMapping("/{id}/excluir")
    public String excluir(@PathVariable Long id, RedirectAttributes ra) {
        Processo p = processoService.buscar(id);
        String numero = p.getNumero();
        processoService.excluir(id);
        anexoStorage.removerPastaProcesso(p);
        auditoria.registrar("PROCESSO_EXCLUIDO", "Processo " + numero);
        ra.addFlashAttribute("msg", "Processo " + numero + " excluido.");
        return "redirect:/processos";
    }

    /** Salva os pareceres (envio/resultado/datas) editados na tela de detalhe. */
    @PostMapping("/{id}/pareceres")
    public String salvarPareceres(@PathVariable Long id,
                                  @RequestParam(required = false) java.util.List<Long> parecerId,
                                  @RequestParam(required = false) java.util.List<String> resultado,
                                  @RequestParam(required = false) java.util.List<String> dataEnvio,
                                  RedirectAttributes ra) {
        Processo p = processoService.buscar(id);
        if (parecerId != null) {
            for (int i = 0; i < parecerId.size(); i++) {
                Long pid = parecerId.get(i);
                String res = (resultado != null && i < resultado.size()) ? resultado.get(i) : "";
                String env = (dataEnvio != null && i < dataEnvio.size()) ? dataEnvio.get(i) : "";
                p.getPareceres().stream()
                    .filter(par -> par.getId().equals(pid))
                    .findFirst()
                    .ifPresent(par -> {
                        // Pareceres votados diretamente pelo avaliador no portal sao IMUTAVEIS:
                        // o operador nao pode alterar o resultado (nao-repudio). Apenas a
                        // dataEnvio pode ser preservada (e vem como hidden no form).
                        if (par.getOrigem() == OrigemParecer.AVALIADOR_SISTEMA) {
                            return; // ignora silenciosamente qualquer alteracao de resultado
                        }
                        par.setDataEnvio((env == null || env.isBlank()) ? null : LocalDate.parse(env));
                        if (res == null || res.isBlank()) {
                            par.setResultado(null);
                        } else {
                            par.setResultado(ResultadoParecer.valueOf(res));
                            if (par.getDataResposta() == null) {
                                par.setDataResposta(LocalDate.now());
                            }
                        }
                    });
            }
        }
        processoService.salvar(p);
        // Etapa 6/7: se um medico pediu informacao (e ainda nao houve decisao),
        // o status passa a SOLICITA_INFORMACAO; senao permanece ENVIADO.
        processoService.atualizarStatusPorPareceres(id);
        // Decisao automatica: se a maioria foi formada e as pre-condicoes
        // (sem pareceres sem anexo) estiverem satisfeitas, decide imediatamente.
        Processo pDecidido = processoService.tentarDecisaoAutomatica(id);
        if (pDecidido.getStatus().isFinalizado()) {
            // Gera automaticamente o Oficio (se indeferido) e o Relatorio Final
            try { decisaoFinalService.gerarDocumentos(pDecidido); }
            catch (IllegalStateException e) { ra.addFlashAttribute("erro", e.getMessage()); }
            ra.addFlashAttribute("msg", "Pareceres atualizados. Decisao automatica: "
                + pDecidido.getStatus().getDescricao() + ".");
            return "redirect:/processos/" + id;
        }
        ra.addFlashAttribute("msg", "Pareceres atualizados.");
        return "redirect:/processos/" + id + "#respostas";
    }

    /**
     * Registra o REENVIO ao solicitante do pedido de informacao complementar
     * (quando um avaliador pede mais dados). Opcionalmente anexa a copia do
     * e-mail enviado (TipoAnexo.INFO_COMPLEMENTAR). Mantem o processo em
     * SOLICITA_INFORMACAO (PAUSA) ate a resposta chegar e a analise ser retomada.
     */
    @PostMapping("/{id}/solicitar-info")
    public String solicitarInfo(@PathVariable Long id,
                                @RequestParam(value = "arquivo", required = false) MultipartFile arquivo,
                                RedirectAttributes ra) {
        Processo p = processoService.buscar(id);
        if (arquivo != null && !arquivo.isEmpty()) {
            try {
                anexoStorage.salvar(p, TipoAnexo.INFO_COMPLEMENTAR,
                    "Copia do e-mail de pedido de informacao complementar ao solicitante", arquivo);
                auditoria.registrar("ANEXO_ADICIONADO",
                    "Processo " + p.getNumero() + " - Pedido de informacao complementar (enviado)");
            } catch (IllegalArgumentException | IOException e) {
                ra.addFlashAttribute("erro", "Falha ao anexar o pedido de informacao: " + e.getMessage());
                return "redirect:/processos/" + id + "#respostas";
            }
        }
        auditoria.registrar("INFO_COMPLEMENTAR_SOLICITADA", "Processo " + p.getNumero());
        ra.addFlashAttribute("msg",
            "Pedido de informacao complementar registrado. O processo permanece em pausa "
            + "ate a resposta do solicitante.");
        return "redirect:/processos/" + id + "#respostas";
    }

    /**
     * Registra o RECEBIMENTO da informacao complementar do solicitante e RETOMA
     * a analise: o processo volta de SOLICITA_INFORMACAO para ENVIADO (fluxo de
     * Respostas/Decisao). Opcionalmente anexa a resposta recebida.
     */
    @PostMapping("/{id}/retomar-analise")
    public String retomarAnalise(@PathVariable Long id,
                                 @RequestParam(value = "arquivo", required = false) MultipartFile arquivo,
                                 RedirectAttributes ra) {
        Processo p = processoService.buscar(id);
        if (arquivo != null && !arquivo.isEmpty()) {
            try {
                anexoStorage.salvar(p, TipoAnexo.INFO_COMPLEMENTAR,
                    "Resposta com as informacoes complementares do solicitante", arquivo);
                auditoria.registrar("ANEXO_ADICIONADO",
                    "Processo " + p.getNumero() + " - Informacao complementar (recebida)");
            } catch (IllegalArgumentException | IOException e) {
                ra.addFlashAttribute("erro", "Falha ao anexar a resposta: " + e.getMessage());
                return "redirect:/processos/" + id + "#respostas";
            }
        }
        processoService.retomarAposInformacao(id);
        auditoria.registrar("ANALISE_RETOMADA",
            "Processo " + p.getNumero() + " - pareceres em 'Solicita informacao' reabertos como pendencia limpa");
        // Apos retomar, tenta decisao automatica caso os votos ja formem maioria
        // (pode ocorrer quando so um medico havia pedido info e os demais ja votaram).
        Processo pRetomado = processoService.tentarDecisaoAutomatica(id);
        if (pRetomado.getStatus().isFinalizado()) {
            try { decisaoFinalService.gerarDocumentos(pRetomado); }
            catch (IllegalStateException e) { ra.addFlashAttribute("erro", e.getMessage()); }
            auditoria.registrar("PROCESSO_DECIDIDO",
                "Processo " + pRetomado.getNumero() + " - decisao automatica: "
                + pRetomado.getStatus().getDescricao());
            ra.addFlashAttribute("msg",
                "Informacao complementar recebida. Analise retomada e decisao automatica aplicada: "
                + pRetomado.getStatus().getDescricao() + ".");
            return "redirect:/processos/" + id;
        }
        ra.addFlashAttribute("msg",
            "Informacao complementar recebida. Analise retomada - registre os pareceres definitivos.");
        return "redirect:/processos/" + id + "#respostas";
    }

    /**
     * Registra a data de envio de hoje para TODOS os 3 medicos do processo
     * (acao unica). Gera automaticamente o PDF de solicitacao de avaliacao e
     * opcionalmente aceita o PDF do e-mail de envio como anexo.
     */
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

        // 1) Copia da solicitacao original (com nome completo) — anexo manual.
        if (arquivo != null && !arquivo.isEmpty()) {
            try {
                anexoStorage.removerPorTipo(id, TipoAnexo.SOLICITACAO_RECEBIDA);
                anexoStorage.salvar(p, TipoAnexo.SOLICITACAO_RECEBIDA,
                    "Copia da solicitacao original recebida", arquivo);
                auditoria.registrar("ANEXO_ADICIONADO",
                    "Processo " + p.getNumero() + " - " + TipoAnexo.SOLICITACAO_RECEBIDA.getDescricao());
            } catch (IOException e) {
                ra.addFlashAttribute("erro", "Falha ao anexar a solicitacao original: " + e.getMessage());
                return "redirect:/processos/" + id + "#recebimento";
            }
        }

        ra.addFlashAttribute("msg", "Recebimento registrado: solicitacao original anexada.");
        return "redirect:/processos/" + id + "#recebimento";
    }

    @PostMapping("/{id}/registrar-envio")
    public String registrarEnvio(@PathVariable Long id,
                                 @RequestParam(value = "arquivo", required = false) MultipartFile arquivo,
                                 RedirectAttributes ra) {
        Processo p = processoService.buscar(id);
        LocalDate hoje = LocalDate.now();

        // O PDF dos avaliadores agora e montado SO com os documentos clinicos
        // anonimizados (PDF) anexados pelo operador: funde-os em um unico PDF e
        // carimba, em cada pagina, um cabecalho com nº do processo + INICIAIS do
        // paciente (NUNCA o nome completo - imparcialidade do julgamento). Sem a
        // folha-rosto gerada pelo sistema. A solicitacao ORIGINAL recebida (nome
        // completo) NUNCA entra aqui. Sem nenhum documento clinico PDF nao ha o
        // que enviar: bloqueia o envio.
        java.util.List<byte[]> partes = new java.util.ArrayList<>();
        java.util.List<String> ignorados = new java.util.ArrayList<>();
        try {
            for (Anexo doc : p.getAnexos()) {
                if (doc.getTipo() != TipoAnexo.DOCUMENTO_CLINICO_AVALIADOR) {
                    continue;
                }
                boolean ehPdf = doc.getContentType() != null
                    && doc.getContentType().toLowerCase().contains("application/pdf");
                if (!ehPdf) {
                    ignorados.add(doc.getNomeArquivo());
                    continue;
                }
                partes.add(java.nio.file.Files.readAllBytes(anexoStorage.resolverArquivo(doc)));
            }
        } catch (IOException e) {
            ra.addFlashAttribute("erro", "Falha ao ler os documentos clinicos: " + e.getMessage());
            return "redirect:/processos/" + id + "#envio";
        }

        if (partes.isEmpty()) {
            String detalhe = ignorados.isEmpty()
                ? "Anexe ao menos um documento clinico (PDF) antes de registrar o envio."
                : "Os documentos anexados nao sao PDF e nao podem ser consolidados ("
                    + String.join(", ", ignorados) + "). Anexe ao menos um documento clinico em PDF.";
            ra.addFlashAttribute("erro", detalhe);
            return "redirect:/processos/" + id + "#envio";
        }

        // So a partir daqui o envio e efetivado.
        p.getPareceres().forEach(par -> par.setDataEnvio(hoje));
        processoService.salvar(p);
        // Etapa 5: processo passa de SOLICITADO para ENVIADO.
        processoService.registrarEnvio(id);

        try {
            anexoStorage.removerPorTipo(id, TipoAnexo.SOLICITACAO_AVALIADOR);
            byte[] consolidado = solicitacaoAvaliadorService.consolidar(partes);
            byte[] pdfSolicitacao = solicitacaoAvaliadorService.carimbarCabecalho(consolidado, p);
            String nomeSolicitacao = SolicitacaoAvaliadorService.nomeArquivoOficial(p);
            anexoStorage.salvarBytes(p, TipoAnexo.SOLICITACAO_AVALIADOR,
                "Copia da solicitacao para envio as equipes (documentos clinicos anonimizados com cabecalho; nome completo suprimido)",
                nomeSolicitacao, "application/pdf", pdfSolicitacao);
            auditoria.registrar("ANEXO_ADICIONADO",
                "Processo " + p.getNumero() + " - Solicitacao PDF consolidada (cabecalho carimbado) gerada automaticamente");

            if (!ignorados.isEmpty()) {
                ra.addFlashAttribute("aviso",
                    "Estes documentos clinicos nao sao PDF e ficaram de fora do PDF consolidado: "
                        + String.join(", ", ignorados) + ".");
            }
        } catch (IOException | RuntimeException e) {
            String msg = (e instanceof IOException)
                ? "Falha ao gerar a solicitacao PDF: " + e.getMessage()
                : e.getMessage();
            ra.addFlashAttribute("erro", "Envio registrado, mas " + msg);
            return "redirect:/processos/" + id + "#envio";
        }

        if (arquivo != null && !arquivo.isEmpty()) {
            try {
                anexoStorage.salvar(p, TipoAnexo.EMAIL_ENVIADO_AVALIADORES,
                    "E-mail de envio aos avaliadores (" + hoje + ")", arquivo);
                auditoria.registrar("ANEXO_ADICIONADO",
                    "Processo " + p.getNumero() + " - " + TipoAnexo.EMAIL_ENVIADO_AVALIADORES.getDescricao());
            } catch (IOException e) {
                ra.addFlashAttribute("erro", "Envio registrado, mas falhou ao anexar o arquivo: " + e.getMessage());
                return "redirect:/processos/" + id + "#envio";
            }
        }
        auditoria.registrar("ENVIO_AVALIADORES_REGISTRADO", "Processo " + p.getNumero());
        ra.addFlashAttribute("msg", "Envio aos avaliadores registrado em " + hoje.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")) + ".");
        return "redirect:/processos/" + id + "#envio";
    }

    /**
     * Anexa um documento clinico ANONIMIZADO (sem nome do paciente) que sera
     * consolidado, junto com a folha-rosto, no PDF unico enviado aos avaliadores
     * no passo 2 (envio). Mantem o operador na aba Envio.
     */
    @PostMapping("/{id}/documento-clinico")
    public String anexarDocumentoClinico(@PathVariable Long id,
                                         @RequestParam("arquivo") MultipartFile arquivo,
                                         @RequestParam(required = false) String descricao,
                                         RedirectAttributes ra) {
        Processo p = processoService.buscar(id);
        try {
            String desc = (descricao != null && !descricao.isBlank())
                ? descricao : "Documento clinico anonimizado para os avaliadores";
            anexoStorage.salvar(p, TipoAnexo.DOCUMENTO_CLINICO_AVALIADOR, desc, arquivo);
            auditoria.registrar("ANEXO_ADICIONADO",
                "Processo " + p.getNumero() + " - " + TipoAnexo.DOCUMENTO_CLINICO_AVALIADOR.getDescricao());
            ra.addFlashAttribute("msg", "Documento clinico anexado. Sera consolidado no PDF dos avaliadores ao registrar o envio.");
        } catch (IllegalArgumentException | IOException e) {
            ra.addFlashAttribute("erro", "Falha ao anexar documento clinico: " + e.getMessage());
        }
        return "redirect:/processos/" + id + "#envio";
    }

    @PostMapping("/{id}/decidir")
    public String decidir(@PathVariable Long id,
                          @RequestParam StatusProcesso decisao,
                          @RequestParam(required = false) String motivoIndeferimento,
                          RedirectAttributes ra) {
        // So aceita decisoes reais; estados de andamento nao sao "decisoes".
        if (decisao != StatusProcesso.DEFERIDO
                && decisao != StatusProcesso.INDEFERIDO
                && decisao != StatusProcesso.CANCELADO) {
            ra.addFlashAttribute("erro", "Decisao invalida: escolha Deferido, Indeferido ou Cancelado.");
            return "redirect:/processos/" + id;
        }
        if (decisao == StatusProcesso.INDEFERIDO
                && (motivoIndeferimento == null || motivoIndeferimento.isBlank())) {
            ra.addFlashAttribute("erro", "Indeferimento exige o motivo.");
            return "redirect:/processos/" + id;
        }
        // PAUSA: enquanto aguarda informacao complementar, nao pode deferir/indeferir.
        Processo atual = processoService.buscar(id);
        if (atual.getStatus() == StatusProcesso.SOLICITA_INFORMACAO
                && (decisao == StatusProcesso.DEFERIDO || decisao == StatusProcesso.INDEFERIDO)) {
            ra.addFlashAttribute("erro",
                "Processo aguardando informacao complementar do solicitante. "
                + "Registre o recebimento da informacao (retomar analise) antes de decidir.");
            return "redirect:/processos/" + id + "#respostas";
        }
        // Regra: Deferido exige votos suficientes (coordenador pode deferir sozinho ou 2/3).
        long minFavoraveis = processoService.temVotoCoordenadorFavoravel(atual)
            ? 1 : ProcessoService.FAVORAVEIS_PARA_DEFERIR;
        if (decisao == StatusProcesso.DEFERIDO
                && processoService.contarFavoraveis(atual) < minFavoraveis) {
            ra.addFlashAttribute("erro", "Deferimento exige no minimo "
                + minFavoraveis + " parecer(es) favoravel(is).");
            return "redirect:/processos/" + id;
        }
        if (decisao == StatusProcesso.INDEFERIDO
                && processoService.contarNaoFavoraveis(atual) < ProcessoService.DESFAVORAVEIS_PARA_INDEFERIR) {
            ra.addFlashAttribute("erro", "Indeferimento exige no minimo "
                + ProcessoService.DESFAVORAVEIS_PARA_INDEFERIR + " pareceres desfavoraveis.");
            return "redirect:/processos/" + id;
        }
        // Regra: toda resposta de medico recebida precisa ter o anexo comprobatorio.
        if (decisao == StatusProcesso.DEFERIDO || decisao == StatusProcesso.INDEFERIDO) {
            var semAnexo = processoService.pareceresRecebidosSemAnexo(atual);
            if (!semAnexo.isEmpty()) {
                String nomes = semAnexo.stream()
                    .map(par -> par.getMembro().getNome())
                    .collect(java.util.stream.Collectors.joining(", "));
                ra.addFlashAttribute("erro",
                    "Anexe a resposta dos medicos antes de decidir. Sem anexo: " + nomes + ".");
                return "redirect:/processos/" + id + "#respostas";
            }
        }
        Processo p = processoService.decidir(id, decisao, motivoIndeferimento);
        try { decisaoFinalService.gerarDocumentos(p); }
        catch (IllegalStateException e) { ra.addFlashAttribute("erro", e.getMessage()); }
        auditoria.registrar("PROCESSO_DECIDIDO",
            "Processo " + p.getNumero() + " - " + decisao.getDescricao());
        ra.addFlashAttribute("msg", "Decisao registrada: " + decisao.getDescricao());
        return "redirect:/processos/" + id;
    }



    /** Atualiza dados de finalizacao: datas do oficio e resposta ao solicitante. */
    @PostMapping("/{id}/finalizacao")
    public String finalizacao(@PathVariable Long id,
                              @RequestParam(required = false)
                              @org.springframework.format.annotation.DateTimeFormat(iso =
                                  org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
                              LocalDate dataEmissaoOficio,
                              @RequestParam(required = false)
                              @org.springframework.format.annotation.DateTimeFormat(iso =
                                  org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
                              LocalDate dataEnvioOficio,
                              @RequestParam(required = false, defaultValue = "false") boolean emailEnviadoSolicitante,
                              RedirectAttributes ra) {
        Processo p = processoService.buscar(id);
        p.setDataEmissaoOficio(dataEmissaoOficio);
        p.setDataEnvioOficio(dataEnvioOficio);
        p.setEmailEnviadoSolicitante(emailEnviadoSolicitante);
        processoService.salvar(p);
        ra.addFlashAttribute("msg", "Dados de finalizacao atualizados.");
        return "redirect:/processos/" + id + "#finalizacao";
    }

    /** Upload do Oficio de Indeferimento na aba Finalizacao (so para processos INDEFERIDOS). */
    @PostMapping("/{id}/oficio-upload")
    public String uploadOficio(@PathVariable Long id,
                               @RequestParam("arquivo") MultipartFile arquivo,
                               RedirectAttributes ra) {
        Processo p = processoService.buscar(id);
        if (p.getStatus() != StatusProcesso.INDEFERIDO) {
            ra.addFlashAttribute("erro", "Upload de oficio so e permitido para processos Indeferidos.");
            return "redirect:/processos/" + id + "#finalizacao";
        }
        try {
            anexoStorage.removerPorTipo(id, TipoAnexo.OFICIO_INDEFERIMENTO);
            anexoStorage.salvar(p, TipoAnexo.OFICIO_INDEFERIMENTO,
                "Oficio de indeferimento", arquivo);
            auditoria.registrar("ANEXO_ADICIONADO",
                "Processo " + p.getNumero() + " - " + TipoAnexo.OFICIO_INDEFERIMENTO.getDescricao());
            ra.addFlashAttribute("msg", "Oficio de indeferimento anexado.");
        } catch (IllegalArgumentException | IOException e) {
            ra.addFlashAttribute("erro", "Falha ao anexar o oficio: " + e.getMessage());
        }
        return "redirect:/processos/" + id + "#finalizacao";
    }

    /** Upload do comprovante de envio da resposta ao solicitante (passo 6). */
    @PostMapping("/{id}/comprovante-envio-solicitante")
    public String uploadComprovanteEnvioSolicitante(@PathVariable Long id,
                                                    @RequestParam("arquivo") MultipartFile arquivo,
                                                    RedirectAttributes ra) {
        Processo p = processoService.buscar(id);
        try {
            anexoStorage.removerPorTipo(id, TipoAnexo.COMPROVANTE_ENVIO_SOLICITANTE);
            anexoStorage.salvar(p, TipoAnexo.COMPROVANTE_ENVIO_SOLICITANTE,
                "Comprovante de envio da resposta ao solicitante", arquivo);
            auditoria.registrar("ANEXO_ADICIONADO",
                "Processo " + p.getNumero() + " - " + TipoAnexo.COMPROVANTE_ENVIO_SOLICITANTE.getDescricao());
            ra.addFlashAttribute("msg", "Comprovante de envio ao solicitante anexado.");
        } catch (IllegalArgumentException | IOException e) {
            ra.addFlashAttribute("erro", "Falha ao anexar o comprovante: " + e.getMessage());
        }
        return "redirect:/processos/" + id + "#finalizacao";
    }

    /** Upload do Comprovante SNT na aba Finalizacao (so para processos DEFERIDOS). */
    @PostMapping("/{id}/comprovante-snt")
    public String uploadComprovanteSnt(@PathVariable Long id,
                                       @RequestParam("arquivo") MultipartFile arquivo,
                                       RedirectAttributes ra) {
        Processo p = processoService.buscar(id);
        if (p.getStatus() != StatusProcesso.DEFERIDO) {
            ra.addFlashAttribute("erro", "Upload do comprovante SNT so e permitido para processos Deferidos.");
            return "redirect:/processos/" + id + "#finalizacao";
        }
        try {
            anexoStorage.removerPorTipo(id, TipoAnexo.COMPROVANTE_SNT);
            anexoStorage.salvar(p, TipoAnexo.COMPROVANTE_SNT,
                "Comprovante de insercao da urgencia renal no SNT", arquivo);
            auditoria.registrar("ANEXO_ADICIONADO",
                "Processo " + p.getNumero() + " - " + TipoAnexo.COMPROVANTE_SNT.getDescricao());
            ra.addFlashAttribute("msg", "Comprovante SNT anexado.");
        } catch (IllegalArgumentException | IOException e) {
            ra.addFlashAttribute("erro", "Falha ao anexar o comprovante SNT: " + e.getMessage());
        }
        return "redirect:/processos/" + id + "#finalizacao";
    }

    @PostMapping("/{id}/anexos")
    public String anexar(@PathVariable Long id,
                         @RequestParam TipoAnexo tipo,
                         @RequestParam(required = false) String descricao,
                         @RequestParam("arquivo") MultipartFile arquivo,
                         RedirectAttributes ra) {
        Processo p = processoService.buscar(id);
        try {
            anexoStorage.salvar(p, tipo, descricao, arquivo);
            auditoria.registrar("ANEXO_ADICIONADO",
                "Processo " + p.getNumero() + " - " + tipo.getDescricao());
            ra.addFlashAttribute("msg", "Anexo enviado.");
        } catch (IllegalArgumentException | IOException e) {
            ra.addFlashAttribute("erro", "Falha ao anexar: " + e.getMessage());
        }
        return "redirect:/processos/" + id + "#anexos";
    }

    /**
     * Faz upload do e-mail de resposta de um avaliador especifico, vinculando
     * ao Parecer correspondente (identificado por parecerId).
     */
    @PostMapping("/{id}/resposta-avaliador")
    public String respostaAvaliador(@PathVariable Long id,
                                    @RequestParam Long parecerId,
                                    @RequestParam("arquivo") MultipartFile arquivo,
                                    @RequestParam(required = false) String descricao,
                                    RedirectAttributes ra) {
        Processo p = processoService.buscar(id);
        Parecer parecer = parecerRepository.findById(parecerId)
            .orElseThrow(() -> new IllegalArgumentException("Parecer nao encontrado: " + parecerId));
        if (parecer.getOrigem() == OrigemParecer.AVALIADOR_SISTEMA) {
            ra.addFlashAttribute("erro",
                "Nao e possivel anexar resposta de um avaliador que votou pelo portal (nao-repudio).");
            return "redirect:/processos/" + id + "#respostas";
        }
        try {
            String desc = (descricao != null && !descricao.isBlank())
                ? descricao
                : "Resposta de " + parecer.getMembro().getNome();
            anexoStorage.salvarRespostaAvaliador(p, parecer, desc, arquivo);
            auditoria.registrar("ANEXO_ADICIONADO",
                "Processo " + p.getNumero() + " - Resposta de " + parecer.getMembro().getNome());
            ra.addFlashAttribute("msg", "Resposta de " + parecer.getMembro().getNome() + " anexada.");
        } catch (IllegalArgumentException | IOException e) {
            ra.addFlashAttribute("erro", "Falha ao anexar resposta: " + e.getMessage());
        }
        return "redirect:/processos/" + id + "#respostas";
    }

    @GetMapping("/{id}/relatorio")
    public ResponseEntity<byte[]> relatorio(@PathVariable Long id) {
        Processo p = processoService.buscar(id);
        byte[] pdf = relatorioService.gerar(p);
        String nome = "relatorio-processo-" + p.getNumero().replace("/", "-") + ".pdf";
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + nome + "\"")
            .body(pdf);
    }

    @PostMapping("/anexos/{anexoId}/excluir")
    public String excluirAnexo(@PathVariable Long anexoId, RedirectAttributes ra) {
        // Bloqueia a exclusao de RESPOSTA_AVALIADOR de parecer votado pelo portal
        // (nao-repudio: o registro autenticado nao pode ser apagado pelo operador).
        Anexo anexoParaExcluir = anexoStorage.buscar(anexoId);
        if (anexoParaExcluir.getTipo() == TipoAnexo.RESPOSTA_AVALIADOR
                && anexoParaExcluir.getParecer() != null
                && anexoParaExcluir.getParecer().getOrigem() == OrigemParecer.AVALIADOR_SISTEMA) {
            Long pid = anexoParaExcluir.getProcesso().getId();
            ra.addFlashAttribute("erro",
                "Nao e possivel remover a resposta de um avaliador que votou pelo portal (nao-repudio).");
            return "redirect:/processos/" + pid + "#respostas";
        }
        Long processoId = anexoStorage.excluir(anexoId);
        auditoria.registrar("ANEXO_REMOVIDO", "Processo id " + processoId);
        ra.addFlashAttribute("msg", "Anexo removido.");
        return "redirect:/processos/" + processoId + "#anexos";
    }

    @GetMapping("/{id}/oficio")
    public ResponseEntity<byte[]> oficio(@PathVariable Long id) {
        Processo p = processoService.buscar(id);
        byte[] pdf = oficioService.gerar(p);
        String nome = "oficio-indeferimento-" + p.getNumero().replace("/", "-") + ".pdf";
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + nome + "\"")
            .body(pdf);
    }

    @GetMapping("/anexos/{anexoId}/download")
    public ResponseEntity<Resource> baixarAnexo(@PathVariable Long anexoId) throws MalformedURLException {
        Anexo anexo = anexoStorage.buscar(anexoId);
        Path arquivo = anexoStorage.resolverArquivo(anexo);
        Resource resource = new UrlResource(arquivo.toUri());
        if (!resource.exists() || !resource.isReadable()) {
            return ResponseEntity.notFound().build();
        }
        String contentType = anexo.getContentType() != null
            ? anexo.getContentType() : MediaType.APPLICATION_OCTET_STREAM_VALUE;
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(contentType))
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + anexo.getNomeArquivo() + "\"")
            .body(resource);
    }
}
