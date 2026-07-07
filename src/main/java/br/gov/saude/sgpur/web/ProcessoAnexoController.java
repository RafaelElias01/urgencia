package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.*;
import br.gov.saude.sgpur.service.AnexoStorageService;
import br.gov.saude.sgpur.service.AuditoriaService;
import br.gov.saude.sgpur.service.GeminiService;
import br.gov.saude.sgpur.service.OficioService;
import br.gov.saude.sgpur.service.ProcessoService;
import br.gov.saude.sgpur.service.ProcessoValidator;
import br.gov.saude.sgpur.service.RelatorioService;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

/**
 * Passos 5 e 6 do fluxo (oficio/comprovante e resposta ao solicitante) e o
 * gerenciamento de anexos: upload/download/remocao e a geracao de PDFs
 * (oficio, relatorio).
 */
@Controller
@RequestMapping("/processos")
public class ProcessoAnexoController {

    private final ProcessoService processoService;
    private final ProcessoValidator validator;
    private final AnexoStorageService anexoStorage;
    private final AuditoriaService auditoria;
    private final OficioService oficioService;
    private final RelatorioService relatorioService;
    private final GeminiService geminiService;

    public ProcessoAnexoController(ProcessoService processoService,
                                   ProcessoValidator validator,
                                   AnexoStorageService anexoStorage,
                                   AuditoriaService auditoria,
                                   OficioService oficioService,
                                   RelatorioService relatorioService,
                                   GeminiService geminiService) {
        this.processoService = processoService;
        this.validator = validator;
        this.anexoStorage = anexoStorage;
        this.auditoria = auditoria;
        this.oficioService = oficioService;
        this.relatorioService = relatorioService;
        this.geminiService = geminiService;
    }

    /** Atualiza as datas do oficio de indeferimento (aba Finalizacao). */
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
                              RedirectAttributes ra) {
        Processo p = processoService.buscar(id);
        p.setDataEmissaoOficio(dataEmissaoOficio);
        p.setDataEnvioOficio(dataEnvioOficio);
        processoService.salvar(p);
        ra.addFlashAttribute("msg", "Dados de finalizacao atualizados.");
        return "redirect:/processos/" + id + "#finalizacao";
    }

    /** Confirma o envio da resposta ao solicitante (aba Resposta ao solicitante). */
    @PostMapping("/{id}/resposta-solicitante")
    public String respostaSolicitante(@PathVariable Long id,
                              @RequestParam(required = false, defaultValue = "false") boolean emailEnviadoSolicitante,
                              RedirectAttributes ra) {
        Processo p = processoService.buscar(id);
        // Regra: nao da para confirmar a resposta ao solicitante sem o
        // comprovante que a sustenta - comprovante SNT no Deferido (simetrico
        // ao oficio no Indeferido). Centralizado em ProcessoValidator.
        if (emailEnviadoSolicitante) {
            var bloqueio = validator.validarRespostaSolicitante(p);
            if (bloqueio.isPresent()) {
                ra.addFlashAttribute("erro", bloqueio.get());
                return "redirect:/processos/" + id + "#finalizacao";
            }
        }
        p.setEmailEnviadoSolicitante(emailEnviadoSolicitante);
        processoService.salvar(p);
        ra.addFlashAttribute("msg", "Finalizacao salva.");
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
        if (validator.edicaoBloqueada(p)) {
            ra.addFlashAttribute("erro", ProcessoValidator.MSG_ENCERRADO);
            return "redirect:/processos/" + id + "#anexos";
        }
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

    @PostMapping("/anexos/{anexoId}/excluir")
    public String excluirAnexo(@PathVariable Long anexoId, RedirectAttributes ra) {
        // Bloqueia a exclusao de RESPOSTA_AVALIADOR de parecer votado pelo portal
        // (nao-repudio: o registro autenticado nao pode ser apagado pelo operador).
        Anexo anexoParaExcluir = anexoStorage.buscar(anexoId);
        // Processo encerrado: nenhum anexo pode ser removido (edicao travada).
        if (validator.edicaoBloqueada(anexoParaExcluir.getProcesso())) {
            Long pid = anexoParaExcluir.getProcesso().getId();
            ra.addFlashAttribute("erro", ProcessoValidator.MSG_ENCERRADO);
            return "redirect:/processos/" + pid + "#anexos";
        }
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

    /**
     * Resume, via IA, o conteudo textual de um anexo PDF (ex.: documento
     * clinico anexado). Extrai o texto localmente (OpenPDF) e envia so o
     * texto extraido para a API - o arquivo em si nao e enviado a terceiros.
     */
    @GetMapping("/anexos/{anexoId}/resumo-ia")
    @ResponseBody
    public IaTextoResponse resumoAnexoIa(@PathVariable Long anexoId) {
        if (!geminiService.isDisponivel()) {
            return IaTextoResponse.erro("Assistencia por IA nao configurada.");
        }
        Anexo anexo = anexoStorage.buscar(anexoId);
        if (anexo.getContentType() == null
                || !anexo.getContentType().toLowerCase(java.util.Locale.ROOT).contains("application/pdf")) {
            return IaTextoResponse.erro("Resumo por IA disponivel apenas para anexos em PDF.");
        }
        String texto;
        try {
            byte[] bytes = Files.readAllBytes(anexoStorage.resolverArquivo(anexo));
            com.lowagie.text.pdf.PdfReader reader = new com.lowagie.text.pdf.PdfReader(bytes);
            var extractor = new com.lowagie.text.pdf.parser.PdfTextExtractor(reader);
            StringBuilder sb = new StringBuilder();
            int paginas = Math.min(reader.getNumberOfPages(), 20);
            for (int pagina = 1; pagina <= paginas; pagina++) {
                sb.append(extractor.getTextFromPage(pagina)).append('\n');
            }
            reader.close();
            texto = sb.toString();
        } catch (IOException e) {
            return IaTextoResponse.erro("Falha ao ler o PDF: " + e.getMessage());
        }
        if (texto.isBlank()) {
            return IaTextoResponse.erro("Nao foi possivel extrair texto deste PDF (pode ser uma imagem digitalizada).");
        }
        // Limita o tamanho enviado a API (documentos muito longos sao truncados).
        String textoLimitado = texto.length() > 20000 ? texto.substring(0, 20000) : texto;
        String prompt = "Voce e um assistente administrativo de um orgao publico de saude do Brasil. "
            + "Resuma em ate 5 frases, em portugues do Brasil, o conteudo clinico/administrativo "
            + "do documento abaixo, destacando os pontos relevantes para analise de um pedido de "
            + "urgencia renal. Responda apenas com o resumo, sem introducao.\n\n"
            + "Documento:\n" + textoLimitado;
        return geminiService.perguntar(prompt)
            .map(IaTextoResponse::sucesso)
            .orElseGet(() -> IaTextoResponse.erro("Falha ao consultar a IA. Tente novamente."));
    }
}
