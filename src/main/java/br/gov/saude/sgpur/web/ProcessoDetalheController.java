package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.*;
import br.gov.saude.sgpur.service.AnexoStorageService;
import br.gov.saude.sgpur.service.AuditoriaService;
import br.gov.saude.sgpur.service.ConflitoEquipeMatcher;
import br.gov.saude.sgpur.service.EmailTemplateService;
import br.gov.saude.sgpur.service.FluxoProcessoService;
import br.gov.saude.sgpur.service.GeminiService;
import br.gov.saude.sgpur.service.MembroUrgenciaRenalService;
import br.gov.saude.sgpur.service.ProcessoService;
import br.gov.saude.sgpur.service.ProcessoValidator;
import br.gov.saude.sgpur.service.RelatorioService;
import br.gov.saude.sgpur.service.SolicitacaoOnlineService;
import br.gov.saude.sgpur.service.auditoria.LogAuditoria;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
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
@Transactional
public class ProcessoDetalheController {

    private final ProcessoService processoService;
    private final FluxoProcessoService fluxoService;
    private final EmailTemplateService emailTemplateService;
    private final MembroUrgenciaRenalService membroService;
    private final AnexoStorageService anexoStorage;
    private final AuditoriaService auditoria;
    private final GeminiService geminiService;
    private final ConflitoEquipeMatcher conflitoEquipeMatcher;
    private final RelatorioService relatorioService;
    private final SolicitacaoOnlineService solicitacaoOnlineService;
    private final boolean solicitanteHabilitado;

    public ProcessoDetalheController(ProcessoService processoService,
                                     FluxoProcessoService fluxoService,
                                     EmailTemplateService emailTemplateService,
                                     MembroUrgenciaRenalService membroService,
                                     AnexoStorageService anexoStorage,
                                     AuditoriaService auditoria,
                                     GeminiService geminiService,
                                     ConflitoEquipeMatcher conflitoEquipeMatcher,
                                     RelatorioService relatorioService,
                                     SolicitacaoOnlineService solicitacaoOnlineService,
                                     @Value("${app.solicitante.habilitado:true}") boolean solicitanteHabilitado) {
        this.processoService = processoService;
        this.fluxoService = fluxoService;
        this.emailTemplateService = emailTemplateService;
        this.membroService = membroService;
        this.anexoStorage = anexoStorage;
        this.auditoria = auditoria;
        this.geminiService = geminiService;
        this.conflitoEquipeMatcher = conflitoEquipeMatcher;
        this.relatorioService = relatorioService;
        this.solicitacaoOnlineService = solicitacaoOnlineService;
        this.solicitanteHabilitado = solicitanteHabilitado;
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
    public java.util.List<ResultadoParecer> resultadoValores() {
        // Apenas os votos que um avaliador pode de fato submeter (SEM_RESPOSTA
        // existe so para relatorio/estado interno e sempre seria rejeitado no
        // POST). Mesmo criterio de AvaliadorController.votar.
        return java.util.Arrays.stream(ResultadoParecer.values())
            .filter(ResultadoParecer::isVotoValido)
            .toList();
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
    public String novo(@RequestParam(required = false) Long origemSolicitacaoOnlineId, Model model) {
        // Kill-switch do modulo experimental (ver docs/PLANO-SOLICITANTE.md): se
        // desligado, ignora silenciosamente o parametro - o controller de triagem
        // nem esta registrado nesse caso, mas um link/favorito antigo ainda pode
        // chegar aqui com o parametro na URL.
        if (!solicitanteHabilitado) {
            origemSolicitacaoOnlineId = null;
        }
        Processo p = new Processo();
        p.setDataSituacaoEspecial(LocalDate.now());
        // Modulo experimental "Solicitacao Online" (ver docs/PLANO-SOLICITANTE.md):
        // pre-preenche o formulario com os dados que o solicitante ja enviou pelo
        // portal, para o operador nao redigitar tudo. O operador ainda confere os
        // dados, escolhe os 3 avaliadores e digita o numero normalmente - nada do
        // fluxo de cadastro muda por causa disso.
        if (origemSolicitacaoOnlineId != null) {
            var s = solicitacaoOnlineService.buscar(origemSolicitacaoOnlineId);
            p.setPacienteNome(s.getPacienteNome());
            p.setPacienteRgct(s.getPacienteRgct());
            p.setSolicitanteEquipe(s.getSolicitanteEquipe());
            p.setSolicitanteEmail(s.getSolicitanteEmail());
            p.setDataSituacaoEspecial(s.getDataSituacaoEspecial());
            p.setObservacoes(s.getJustificativaClinica());
        }
        model.addAttribute("origemSolicitacaoOnlineId", origemSolicitacaoOnlineId);
        int ano = Year.now().getValue();
        boolean automatica = processoService.isNumeracaoAutomatica(ano);
        if (!automatica) {
            p.setNumero(processoService.proximoNumero(ano)); // sugestao editavel
        }
        model.addAttribute("processo", p);
        model.addAttribute("numeracaoAutomatica", automatica);
        model.addAttribute("medicos", membroService.listarAtivos());
        model.addAttribute("totalAvaliadores", ProcessoService.AVALIADORES_POR_PROCESSO);
        return "processos/form";
    }

    @PostMapping
    public String salvar(@Valid @ModelAttribute("processo") Processo processo,
                         BindingResult result,
                         @RequestParam(value = "medicoIds", required = false) java.util.List<Long> medicoIds,
                         @RequestParam(required = false) Long origemSolicitacaoOnlineId,
                         Model model, RedirectAttributes ra) {
        // Kill-switch do modulo experimental: ignora o parametro se desligado
        // (ver mesma checagem em novo()).
        if (!solicitanteHabilitado) {
            origemSolicitacaoOnlineId = null;
        }
        int ano = processo.getDataSituacaoEspecial() != null
            ? processo.getDataSituacaoEspecial().getYear() : Year.now().getValue();
        boolean automatica = processoService.isNumeracaoAutomatica(ano);

        // Data da situacao especial define o ANO do processo (numeracao NN/AAAA
        // e RelatorioAnualService agrupam por ela) - um erro de digitacao no ano
        // (ex.: 2016 em vez de 2026, comum em datepicker/digitacao manual) e
        // aceito silenciosamente sem essa checagem, classificando o processo no
        // ano errado sem qualquer aviso. Janela ampla (5 anos passado/futuro)
        // porque a "situacao especial" pode legitimamente ser retroativa.
        if (processo.getDataSituacaoEspecial() != null) {
            int anoAtual = Year.now().getValue();
            if (ano < anoAtual - 5 || ano > anoAtual + 5) {
                result.rejectValue("dataSituacaoEspecial", "foraDoIntervalo",
                    "Data da situacao especial fora do intervalo esperado (verifique o ano digitado).");
            }
        }

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
            model.addAttribute("medicos", membroService.listarAtivos());
            model.addAttribute("totalAvaliadores", ProcessoService.AVALIADORES_POR_PROCESSO);
            model.addAttribute("origemSolicitacaoOnlineId", origemSolicitacaoOnlineId);
            return "processos/form";
        }
        Processo salvo = processoService.cadastrar(processo, medicoIds);
        auditoria.registrar("PROCESSO_CADASTRADO",
            "Processo " + salvo.getNumero() + " - " + salvo.getPacienteNome());
        // Modulo experimental "Solicitacao Online": se este cadastro veio da
        // triagem de uma solicitacao enviada pelo portal, fecha o vinculo -
        // copia os documentos clinicos anexados pelo solicitante para o
        // processo e marca a solicitacao como CONVERTIDA. Feito DEPOIS do
        // cadastro ja ter tido sucesso; se falhar aqui, o processo continua
        // valido (so a solicitacao de origem fica sem o vinculo automatico,
        // corrigivel manualmente).
        if (origemSolicitacaoOnlineId != null) {
            try {
                solicitacaoOnlineService.converter(origemSolicitacaoOnlineId, salvo);
                auditoria.registrar("SOLICITACAO_ONLINE_CONVERTIDA",
                    "Solicitacao " + origemSolicitacaoOnlineId + " -> Processo " + salvo.getNumero());
            } catch (IllegalStateException | IllegalArgumentException e) {
                ra.addFlashAttribute("aviso",
                    "Processo cadastrado, mas houve falha ao vincular a solicitacao online de origem: "
                        + e.getMessage());
            }
        }
        ra.addFlashAttribute("msg", "Processo " + salvo.getNumero() + " cadastrado.");
        return "redirect:/processos/" + salvo.getId();
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    // Sobrescreve o @Transactional (read-write) da classe so nesta rota:
    // e a unica leitura pura do controller, as demais escrevem anexo/estado.
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

        // PAUSA: enquanto aguarda informacao complementar do solicitante, a
        // decisao e a finalizacao ficam bloqueadas ate o operador retomar a analise.
        boolean aguardandoInfo = p.getStatus() == StatusProcesso.SOLICITA_INFORMACAO;
        model.addAttribute("aguardandoInfo", aguardandoInfo);

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

        // Gating das abas (passo 1..5): ate qual passo o operador pode
        // navegar/agir. Calculo centralizado em FluxoProcessoService (mesma
        // fonte de verdade do checklist/wizard), fonte unica para nao
        // divergir da timeline.
        var gating = fluxoService.calcularGating(p);
        model.addAttribute("liberadoRecebimento", gating.liberadoRecebimento());
        model.addAttribute("liberadoEnvio", gating.liberadoEnvio());
        model.addAttribute("liberadoRespostas", gating.liberadoRespostas());
        model.addAttribute("liberadoDecisao", gating.liberadoDecisao());
        model.addAttribute("liberadoFinalizacao", gating.liberadoFinalizacao());

        // Wizard horizontal: mesma fonte de verdade da timeline vertical
        // (FluxoProcessoService), para as duas linhas nunca divergirem.
        var passosWizard = fluxoService.montarPassosWizard(p);
        model.addAttribute("passosWizard", passosWizard);
        String abaAtivaPaneId = passosWizard.stream()
            .filter(passo -> passo.estado() != br.gov.saude.sgpur.service.PassoWizard.Estado.CONCLUIDA)
            .findFirst()
            .map(br.gov.saude.sgpur.service.PassoWizard::paneId)
            .orElse(passosWizard.get(passosWizard.size() - 1).paneId());
        model.addAttribute("abaAtivaPaneId", abaAtivaPaneId);

        // Sub-rotulo dinamico ao lado do status (ex.: "Maioria formada -
        // pronto para decidir"). Calculo centralizado em FluxoProcessoService.
        model.addAttribute("statusSubrotulo", fluxoService.calcularSubrotuloStatus(p));

        return "processos/detalhe";
    }

    @GetMapping("/{id}/editar")
    public String editar(@PathVariable Long id, Model model, RedirectAttributes ra) {
        Processo p = processoService.buscar(id);
        if (bloqueadoPorEncerrado(p, ra)) {
            return "redirect:/processos/" + id;
        }
        model.addAttribute("processo", p);
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
        if (bloqueadoPorEncerrado(p, ra)) {
            return "redirect:/processos/" + id;
        }
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
        // Salva o novo primeiro, so remove o antigo depois de confirmado o
        // sucesso - evita o processo ficar sem nenhuma copia se o save() falhar
        // entre o remover e o salvar.
        if (arquivo != null && !arquivo.isEmpty()) {
            try {
                Anexo novo = anexoStorage.salvar(p, TipoAnexo.SOLICITACAO_RECEBIDA,
                    "Copia da solicitacao original recebida", arquivo);
                anexoStorage.removerAntigosDoTipo(id, TipoAnexo.SOLICITACAO_RECEBIDA, novo.getId());
                auditoria.registrar("ANEXO_ADICIONADO",
                    "Processo " + p.getNumero() + " - " + TipoAnexo.SOLICITACAO_RECEBIDA.getDescricao());
            } catch (IllegalArgumentException | IOException e) {
                ra.addFlashAttribute("erro", "Falha ao anexar a solicitacao original: " + e.getMessage());
                return "redirect:/processos/" + id + "#recebimento";
            }
        }

        // 2) CAPA DO PROCESSO — gerada automaticamente pelo sistema com os
        // dados do solicitante + 3 medicos avaliadores (reaproveita a capa do
        // Relatorio Final). Sempre substitui a capa anterior ao registrar
        // recebimento, garantindo que esteja atualizada. Gera e salva a nova
        // capa ANTES de remover a antiga, para nao ficar sem nenhuma se algo
        // falhar no meio.
        try {
            byte[] pdfCapa = relatorioService.gerarCapaProcesso(p);
            String nomeCapa = "capa-processo-" + p.getNumero().replace("/", "-") + ".pdf";
            Anexo novaCapa = anexoStorage.salvarBytes(p, TipoAnexo.CAPA_PROCESSO,
                "Capa do processo gerada automaticamente no recebimento",
                nomeCapa, "application/pdf", pdfCapa);
            anexoStorage.removerAntigosDoTipo(id, TipoAnexo.CAPA_PROCESSO, novaCapa.getId());
            auditoria.registrar("ANEXO_ADICIONADO",
                "Processo " + p.getNumero() + " - Capa do processo gerada automaticamente");
        } catch (Exception e) {
            ra.addFlashAttribute("erro", "Falha ao gerar a capa do processo: " + e.getMessage());
            return "redirect:/processos/" + id + "#recebimento";
        }

        ra.addFlashAttribute("msg", "Recebimento registrado: solicitacao original anexada e capa gerada.");
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
