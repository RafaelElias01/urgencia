package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.*;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.service.AnexoStorageService;
import br.gov.saude.sgpur.service.AuditoriaService;
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

    public ProcessoController(ProcessoService processoService,
                              FluxoProcessoService fluxoService,
                              EmailTemplateService emailTemplateService,
                              RelatorioService relatorioService,
                              OficioService oficioService,
                              SolicitacaoAvaliadorService solicitacaoAvaliadorService,
                              MembroUrgenciaRenalRepository membroRepository,
                              ParecerRepository parecerRepository,
                              AnexoStorageService anexoStorage,
                              AuditoriaService auditoria) {
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
        model.addAttribute("emails", emailTemplateService.gerar(p));
        // IDs dos pareceres que ja possuem e-mail de resposta anexado
        java.util.Set<Long> pareceresComResposta = p.getAnexos().stream()
            .filter(a -> a.getParecer() != null)
            .map(a -> a.getParecer().getId())
            .collect(java.util.stream.Collectors.toSet());
        model.addAttribute("pareceresComResposta", pareceresComResposta);
        // Anexo do tipo SOLICITACAO_AVALIADOR gerado automaticamente ao registrar envio
        Optional<Anexo> solicitacaoPdf = p.getAnexos().stream()
            .filter(a -> a.getTipo() == TipoAnexo.SOLICITACAO_AVALIADOR)
            .findFirst();
        model.addAttribute("solicitacaoPdf", solicitacaoPdf.orElse(null));
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
        ra.addFlashAttribute("msg", "Pareceres atualizados.");
        return "redirect:/processos/" + id + "#respostas";
    }

    /**
     * Registra a data de envio de hoje para TODOS os 3 medicos do processo
     * (acao unica). Gera automaticamente o PDF de solicitacao de avaliacao e
     * opcionalmente aceita o PDF do e-mail de envio como anexo.
     */
    @PostMapping("/{id}/registrar-envio")
    public String registrarEnvio(@PathVariable Long id,
                                 @RequestParam(value = "arquivo", required = false) MultipartFile arquivo,
                                 RedirectAttributes ra) {
        Processo p = processoService.buscar(id);
        LocalDate hoje = LocalDate.now();
        p.getPareceres().forEach(par -> par.setDataEnvio(hoje));
        processoService.salvar(p);
        // Etapa 5: processo passa de SOLICITADO para ENVIADO.
        processoService.registrarEnvio(id);

        // Gera automaticamente o PDF de solicitacao de avaliacao (sem dados pessoais)
        try {
            anexoStorage.removerPorTipo(id, TipoAnexo.SOLICITACAO_AVALIADOR);
            byte[] pdfSolicitacao = solicitacaoAvaliadorService.gerar(p);
            String nomeSolicitacao = "solicitacao-avaliacao-" + p.getNumero().replace("/", "-") + ".pdf";
            anexoStorage.salvarBytes(p, TipoAnexo.SOLICITACAO_AVALIADOR,
                "Solicitacao de avaliacao gerada automaticamente", nomeSolicitacao, "application/pdf", pdfSolicitacao);
            auditoria.registrar("ANEXO_ADICIONADO",
                "Processo " + p.getNumero() + " - Solicitacao PDF gerada automaticamente");
        } catch (IOException e) {
            ra.addFlashAttribute("erro", "Envio registrado, mas falhou ao gerar a solicitacao PDF: " + e.getMessage());
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
        // Regra (maioria simples): Deferido exige >=2 favoraveis; Indeferido >=2 desfavoraveis.
        Processo atual = processoService.buscar(id);
        if (decisao == StatusProcesso.DEFERIDO
                && processoService.contarFavoraveis(atual) < ProcessoService.FAVORAVEIS_PARA_DEFERIR) {
            ra.addFlashAttribute("erro", "Deferimento exige no minimo "
                + ProcessoService.FAVORAVEIS_PARA_DEFERIR + " pareceres favoraveis.");
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
        // Gera automaticamente o Oficio (se indeferido) e o Relatorio Final, anexando-os.
        if (decisao == StatusProcesso.INDEFERIDO) {
            try {
                if (p.getDataEmissaoOficio() == null) {
                    p.setDataEmissaoOficio(LocalDate.now());
                    processoService.salvar(p);
                }
                anexoStorage.removerPorTipo(id, TipoAnexo.OFICIO_INDEFERIMENTO);
                byte[] of = oficioService.gerar(p);
                String nomeOf = "oficio-indeferimento-" + p.getNumero().replace("/", "-") + ".pdf";
                anexoStorage.salvarBytes(p, TipoAnexo.OFICIO_INDEFERIMENTO,
                    "Oficio de indeferimento gerado na decisao", nomeOf, "application/pdf", of);
            } catch (IOException e) {
                ra.addFlashAttribute("erro", "Decisao salva, mas falhou ao anexar o oficio: " + e.getMessage());
            }
        }
        if (decisao.isFinalizado()) {
            try {
                anexoStorage.removerPorTipo(id, TipoAnexo.RELATORIO_FINAL);
                byte[] pdf = relatorioService.gerar(p);
                String nome = "relatorio-processo-" + p.getNumero().replace("/", "-") + ".pdf";
                anexoStorage.salvarBytes(p, TipoAnexo.RELATORIO_FINAL,
                    "Relatorio final gerado na decisao", nome, "application/pdf", pdf);
            } catch (IOException e) {
                ra.addFlashAttribute("erro", "Decisao salva, mas falhou ao anexar o relatorio: " + e.getMessage());
            }
        }
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
