package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.*;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.service.AnexoStorageService;
import br.gov.saude.sgpur.service.FluxoProcessoService;
import br.gov.saude.sgpur.service.ProcessoService;
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
    private final MembroUrgenciaRenalRepository membroRepository;
    private final AnexoStorageService anexoStorage;

    public ProcessoController(ProcessoService processoService,
                              FluxoProcessoService fluxoService,
                              MembroUrgenciaRenalRepository membroRepository,
                              AnexoStorageService anexoStorage) {
        this.processoService = processoService;
        this.fluxoService = fluxoService;
        this.membroRepository = membroRepository;
        this.anexoStorage = anexoStorage;
    }

    @ModelAttribute("statusValores")
    public StatusProcesso[] statusValores() {
        return StatusProcesso.values();
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
    public String listar(Model model) {
        var processos = processoService.listarTodos();
        model.addAttribute("processos", processos);
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
        model.addAttribute("processo", p);
        int ano = Year.now().getValue();
        model.addAttribute("numeracaoAutomatica", processoService.isNumeracaoAutomatica(ano));
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

        // Numero so e obrigatorio quando a numeracao for manual
        if (!automatica && (processo.getNumero() == null || processo.getNumero().isBlank())) {
            result.rejectValue("numero", "obrigatorio", "Informe o numero do processo (NN/AAAA).");
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
        ra.addFlashAttribute("msg", "Processo " + salvo.getNumero() + " cadastrado.");
        return "redirect:/processos/" + salvo.getId();
    }

    @GetMapping("/{id}")
    public String detalhe(@PathVariable Long id, Model model) {
        Processo p = processoService.buscar(id);
        model.addAttribute("processo", p);
        model.addAttribute("etapas", fluxoService.montarEtapas(p));
        Optional<StatusProcesso> sugestao = processoService.sugerirDecisao(p);
        model.addAttribute("sugestao", sugestao.orElse(null));
        model.addAttribute("favoraveis", processoService.contarFavoraveis(p));
        return "processos/detalhe";
    }

    /** Salva os pareceres (resultado/datas) editados na tela de detalhe. */
    @PostMapping("/{id}/pareceres")
    public String salvarPareceres(@PathVariable Long id,
                                  @RequestParam(required = false) java.util.List<Long> parecerId,
                                  @RequestParam(required = false) java.util.List<String> resultado,
                                  RedirectAttributes ra) {
        Processo p = processoService.buscar(id);
        if (parecerId != null) {
            for (int i = 0; i < parecerId.size(); i++) {
                Long pid = parecerId.get(i);
                String res = (resultado != null && i < resultado.size()) ? resultado.get(i) : "";
                p.getPareceres().stream()
                    .filter(par -> par.getId().equals(pid))
                    .findFirst()
                    .ifPresent(par -> {
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
        ra.addFlashAttribute("msg", "Pareceres atualizados.");
        return "redirect:/processos/" + id + "#pareceres";
    }

    @PostMapping("/{id}/decidir")
    public String decidir(@PathVariable Long id,
                          @RequestParam StatusProcesso decisao,
                          @RequestParam(required = false) String motivoIndeferimento,
                          RedirectAttributes ra) {
        if (decisao == StatusProcesso.INDEFERIDO
                && (motivoIndeferimento == null || motivoIndeferimento.isBlank())) {
            ra.addFlashAttribute("erro", "Indeferimento exige o motivo.");
            return "redirect:/processos/" + id;
        }
        processoService.decidir(id, decisao, motivoIndeferimento);
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
            ra.addFlashAttribute("msg", "Anexo enviado.");
        } catch (IllegalArgumentException | IOException e) {
            ra.addFlashAttribute("erro", "Falha ao anexar: " + e.getMessage());
        }
        return "redirect:/processos/" + id + "#anexos";
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
