package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.*;
import br.gov.saude.sgpur.repository.AnexoRepository;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import br.gov.saude.sgpur.service.AnexoStorageService;
import br.gov.saude.sgpur.service.AuditoriaService;
import br.gov.saude.sgpur.service.DecisaoFinalService;
import br.gov.saude.sgpur.service.Iniciais;
import br.gov.saude.sgpur.service.ProcessoService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.net.MalformedURLException;
import java.nio.file.Path;
import java.security.Principal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Portal do Avaliador — visao restrita para medicos avaliadores autenticados.
 *
 * REGRA DE IMPARCIALIDADE: o avaliador NUNCA ve o nome completo do paciente,
 * a equipe solicitante, os co-avaliadores nem os votos dos outros medicos.
 * Apenas iniciais do paciente sao exibidas (convencao da equipe de Urgencia
 * Renal, nao LGPD). O PDF que o avaliador baixa (SOLICITACAO_AVALIADOR) ja
 * foi gerado anonimizado pelo sistema no momento do envio.
 */
@Controller
@RequestMapping("/avaliador")
public class AvaliadorController {

    private static final Logger log = LoggerFactory.getLogger(AvaliadorController.class);

    private final UsuarioRepository usuarioRepo;
    private final ParecerRepository parecerRepo;
    private final AnexoRepository anexoRepo;
    private final AnexoStorageService anexoStorage;
    private final ProcessoService processoService;
    private final AuditoriaService auditoria;
    private final DecisaoFinalService decisaoFinalService;

    public AvaliadorController(UsuarioRepository usuarioRepo,
                               ParecerRepository parecerRepo,
                               AnexoRepository anexoRepo,
                               AnexoStorageService anexoStorage,
                               ProcessoService processoService,
                               AuditoriaService auditoria,
                               DecisaoFinalService decisaoFinalService) {
        this.usuarioRepo = usuarioRepo;
        this.parecerRepo = parecerRepo;
        this.anexoRepo = anexoRepo;
        this.anexoStorage = anexoStorage;
        this.processoService = processoService;
        this.auditoria = auditoria;
        this.decisaoFinalService = decisaoFinalService;
    }

    /**
     * Painel do medico avaliador logado: contadores consolidados, lista de
     * pendentes e historico das suas avaliacoes.
     *
     * Mostra apenas: numero, iniciais do paciente, datas, resultado proprio,
     * link ao PDF. NUNCA: nome completo, equipe solicitante, co-avaliadores ou
     * votos alheios.
     */
    @GetMapping
    public String lista(Principal principal, Model model) {
        MembroUrgenciaRenal membro = resolverMembro(principal);
        Long membroId = membro.getId();

        // Regra unica de "pendentes do avaliador" (reutilizada pelo badge global).
        List<Parecer> parecersFiltrados = pendentesDoMembro(parecerRepo, membroId);

        // Mapas por processoId — passados ao template para evitar logica na view.
        Map<Long, Anexo> pdfPorProcesso = new HashMap<>();
        Map<Long, String> iniciaisPorProcesso = new HashMap<>();
        // PRAZOS: o dominio NAO possui campo de prazo/SLA/data-limite. Por isso
        // exibimos apenas "dias desde o envio" (hoje - dataEnvio) como informacao
        // auxiliar, sem inventar campo novo nem migracao.
        Map<Long, Long> diasDesdeEnvio = new HashMap<>();
        LocalDate hoje = LocalDate.now();
        for (Parecer par : parecersFiltrados) {
            Long pid = par.getProcesso().getId();
            List<Anexo> pdfs = anexoRepo.findByProcessoIdAndTipo(
                pid, TipoAnexo.SOLICITACAO_AVALIADOR);
            if (!pdfs.isEmpty()) {
                pdfPorProcesso.put(pid, pdfs.get(0));
            }
            iniciaisPorProcesso.put(pid, Iniciais.de(par.getProcesso().getPacienteNome()));
            if (par.getDataEnvio() != null) {
                diasDesdeEnvio.put(pid, ChronoUnit.DAYS.between(par.getDataEnvio(), hoje));
            }
        }

        // Historico: pareceres ja votados pelo membro (mais recente primeiro).
        List<Parecer> historico = parecerRepo
            .findByMembroIdAndResultadoIsNotNullOrderByDataRespostaDesc(membroId);
        Map<Long, String> iniciaisHistorico = new HashMap<>();
        for (Parecer par : historico) {
            iniciaisHistorico.put(par.getId(),
                Iniciais.de(par.getProcesso().getPacienteNome()));
        }

        // Contadores consolidados (reutilizam as queries de contagem do repo).
        long totalAtribuidos = parecerRepo.countByMembroId(membroId);
        long totalAvaliados = parecerRepo.countByMembroIdAndResultadoNotNull(membroId);
        long favoraveis = parecerRepo
            .countByMembroIdAndResultado(membroId, ResultadoParecer.FAVORAVEL);
        long naoFavoraveis = parecerRepo
            .countByMembroIdAndResultado(membroId, ResultadoParecer.NAO_FAVORAVEL);
        long solicitaInfo = parecerRepo
            .countByMembroIdAndResultado(membroId, ResultadoParecer.SOLICITA_INFORMACAO);

        model.addAttribute("pareceres", parecersFiltrados);
        model.addAttribute("pdfPorProcesso", pdfPorProcesso);
        model.addAttribute("iniciaisPorProcesso", iniciaisPorProcesso);
        model.addAttribute("diasDesdeEnvio", diasDesdeEnvio);
        model.addAttribute("historico", historico);
        model.addAttribute("iniciaisHistorico", iniciaisHistorico);
        model.addAttribute("membro", membro);
        model.addAttribute("totalAtribuidos", totalAtribuidos);
        model.addAttribute("totalPendentes", parecersFiltrados.size());
        model.addAttribute("totalAvaliados", totalAvaliados);
        model.addAttribute("favoraveis", favoraveis);
        model.addAttribute("naoFavoraveis", naoFavoraveis);
        model.addAttribute("solicitaInfo", solicitaInfo);
        return "avaliador/lista";
    }

    /**
     * Exibe o formulario de voto para um processo especifico.
     * 403 se: nao for avaliador do processo, parecer ja emitido, processo nao ativo.
     */
    @GetMapping("/{processoId}")
    public String votar(@PathVariable Long processoId, Principal principal, Model model) {
        MembroUrgenciaRenal membro = resolverMembro(principal);
        Parecer parecer = resolverParecerPendente(processoId, membro);

        Processo processo = parecer.getProcesso();
        List<Anexo> pdfsAvaliador = anexoRepo
            .findByProcessoIdAndTipo(processoId, TipoAnexo.SOLICITACAO_AVALIADOR);

        // Apenas iniciais — NUNCA nome completo
        model.addAttribute("iniciais", Iniciais.de(processo.getPacienteNome()));
        model.addAttribute("numero", processo.getNumero());
        model.addAttribute("parecer", parecer);
        model.addAttribute("processo", processo);
        model.addAttribute("pdfsAvaliador", pdfsAvaliador);
        model.addAttribute("resultados", List.of(
            ResultadoParecer.FAVORAVEL,
            ResultadoParecer.NAO_FAVORAVEL,
            ResultadoParecer.SOLICITA_INFORMACAO
        ));
        return "avaliador/votar";
    }

    /**
     * Registra o voto do avaliador autenticado.
     *
     * Grava: resultado, dataResposta=hoje, dataHoraVoto=agora, votadoPor=username,
     * origem=AVALIADOR_SISTEMA. Nao exige anexo (o registro autenticado + IP e a
     * prova de nao-repudio). Chama atualizarStatusPorPareceres para manter a maquina
     * de estados do processo correta (inclusive SOLICITA_INFORMACAO).
     */
    @PostMapping("/{processoId}/votar")
    public String registrarVoto(@PathVariable Long processoId,
                                @RequestParam ResultadoParecer resultado,
                                @RequestParam(required = false) String justificativa,
                                Principal principal,
                                HttpServletRequest request,
                                RedirectAttributes ra) {
        MembroUrgenciaRenal membro = resolverMembro(principal);
        Parecer parecer = resolverParecerPendente(processoId, membro);

        // Registra o voto com nao-repudio completo
        parecer.setResultado(resultado);
        parecer.setDataResposta(LocalDate.now());
        parecer.setDataHoraVoto(LocalDateTime.now());
        parecer.setVotadoPor(principal.getName());
        parecer.setOrigem(OrigemParecer.AVALIADOR_SISTEMA);
        // Justificativa e material INTERNO do operador (nunca vaza a outros
        // avaliadores). Vazio/em-branco vira null para nao poluir o banco.
        String justificativaLimpa = (justificativa == null || justificativa.isBlank())
            ? null : justificativa.trim();
        parecer.setJustificativa(justificativaLimpa);
        parecerRepo.save(parecer);

        // Atualiza o status do processo (pode ir para SOLICITA_INFORMACAO)
        processoService.atualizarStatusPorPareceres(processoId);

        // Decisao automatica: se a maioria foi atingida e nao ha pareceres sem
        // anexo pendentes (AVALIADOR_SISTEMA dispensa o anexo), decide imediatamente.
        Processo pDecidido = processoService.tentarDecisaoAutomatica(processoId);
        if (pDecidido.getStatus().isFinalizado()) {
            try { decisaoFinalService.gerarDocumentos(pDecidido); }
            catch (IllegalStateException e) {
                log.warn("Falha ao gerar documentos finais do processo {} apos decisao automatica no portal: {}",
                    pDecidido.getNumero(), e.getMessage());
            }
            auditoria.registrar("PROCESSO_DECIDIDO",
                "Processo " + pDecidido.getNumero() + " - decisao automatica portal: "
                + pDecidido.getStatus().getDescricao());
        }

        String ip = request.getRemoteAddr();
        auditoria.registrar("PARECER_VOTADO",
            "Processo " + parecer.getProcesso().getNumero()
                + " - " + membro.getNome()
                + " - " + resultado.getDescricao(),
            ip);

        ra.addFlashAttribute("msg",
            "Voto registrado: " + resultado.getDescricao() + ". Obrigado pela avaliacao.");
        return "redirect:/avaliador";
    }

    /**
     * Download do PDF anonimizado (SOLICITACAO_AVALIADOR) do processo pelo
     * proprio avaliador. Antes vinha de /processos/anexos/{id}/download, que
     * exige ROLE_ADMIN/OPERADOR e por isso dava 403 para o avaliador - sem
     * este endpoint o medico nao conseguia ler o material antes de votar.
     * So permite o download se o membro for avaliador do processo (posse
     * verificada via Parecer, independente de ja ter votado ou nao).
     */
    @GetMapping("/{processoId}/pdf/{anexoId}")
    public ResponseEntity<Resource> baixarPdf(@PathVariable Long processoId,
                                              @PathVariable Long anexoId,
                                              Principal principal)
            throws MalformedURLException {
        MembroUrgenciaRenal membro = resolverMembro(principal);
        if (parecerRepo.findByProcessoIdAndMembroId(processoId, membro.getId()).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Voce nao e avaliador deste processo.");
        }
        List<Anexo> pdfs = anexoRepo.findByProcessoIdAndTipo(processoId, TipoAnexo.SOLICITACAO_AVALIADOR);
        Anexo anexo = pdfs.stream().filter(a -> a.getId().equals(anexoId)).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Anexo nao pertence ao material de avaliacao deste processo."));
        Path arquivo = anexoStorage.resolverArquivo(anexo);
        Resource resource = new UrlResource(arquivo.toUri());
        if (!resource.exists() || !resource.isReadable()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + anexo.getNomeArquivo() + "\"")
            .body(resource);
    }

    // -------------------------------------------------------------------------
    // Regra reutilizavel de pendencias (compartilhada com o badge global)
    // -------------------------------------------------------------------------

    /**
     * Pareceres pendentes de voto do membro: sem resultado, ja enviados e cujo
     * processo esta em status ativo para votacao (ENVIADO ou EM_ANALISE).
     *
     * Regra UNICA — usada tanto pela lista do portal quanto pelo contador da
     * navbar ({@code GlobalModelAdvice}) para nao duplicar o criterio.
     */
    static List<Parecer> pendentesDoMembro(ParecerRepository parecerRepo, Long membroId) {
        return parecerRepo
            .findByMembroIdAndResultadoIsNullAndDataEnvioIsNotNull(membroId)
            .stream()
            .filter(par -> {
                StatusProcesso s = par.getProcesso().getStatus();
                return s == StatusProcesso.ENVIADO || s == StatusProcesso.EM_ANALISE;
            })
            .toList();
    }

    // -------------------------------------------------------------------------
    // Helpers privados
    // -------------------------------------------------------------------------

    /**
     * Resolve o membro vinculado ao usuario logado.
     * Lanca 403 se o usuario nao tiver membro vinculado (configuracao incorreta).
     */
    private MembroUrgenciaRenal resolverMembro(Principal principal) {
        Usuario usuario = usuarioRepo.findByUsername(principal.getName())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        if (usuario.getMembro() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Usuario avaliador sem membro vinculado. Contate o administrador.");
        }
        return usuario.getMembro();
    }

    /**
     * Resolve o parecer pendente do membro no processo.
     * Lanca 403 se o membro nao for avaliador do processo, se o parecer ja foi
     * emitido, ou se o processo nao esta em status ativo para votacao.
     */
    private Parecer resolverParecerPendente(Long processoId, MembroUrgenciaRenal membro) {
        Parecer parecer = parecerRepo.findByProcessoIdAndMembroId(processoId, membro.getId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Voce nao e avaliador deste processo."));

        if (parecer.getResultado() != null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Voce ja emitiu seu parecer para este processo.");
        }

        StatusProcesso status = parecer.getProcesso().getStatus();
        if (status != StatusProcesso.ENVIADO && status != StatusProcesso.EM_ANALISE) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Este processo nao esta disponivel para avaliacao (status: "
                    + status.getDescricao() + ").");
        }

        return parecer;
    }
}
