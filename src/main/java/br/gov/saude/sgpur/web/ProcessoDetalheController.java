package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.*;
import br.gov.saude.sgpur.service.AnexoStorageService;
import br.gov.saude.sgpur.service.AuditoriaService;
import br.gov.saude.sgpur.service.ConflitoEquipeMatcher;
import br.gov.saude.sgpur.service.EmailTemplateService;
import br.gov.saude.sgpur.service.ExportacaoProcessoService;
import br.gov.saude.sgpur.service.FluxoProcessoService;
import br.gov.saude.sgpur.service.GeminiService;
import br.gov.saude.sgpur.service.Iniciais;
import br.gov.saude.sgpur.service.MembroUrgenciaRenalService;
import br.gov.saude.sgpur.service.ProcessoService;
import br.gov.saude.sgpur.service.ProcessoValidator;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.service.MensagemSolicitacaoService;
import br.gov.saude.sgpur.service.SolicitacaoOnlineService;
import br.gov.saude.sgpur.repository.AnexoRepository;
import br.gov.saude.sgpur.repository.SolicitacaoOnlineRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import br.gov.saude.sgpur.service.auditoria.LogAuditoria;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;
import java.time.LocalDate;
import java.time.Year;
import java.util.Optional;

/**
 * Criacao, detalhe e edicao/exclusao do processo.
 *
 * <p>Desde 2026-07-27, todo processo nasce OBRIGATORIAMENTE de uma
 * {@code SolicitacaoOnline} convertida pelo Portal do Solicitante - nao ha
 * mais cadastro manual "do zero". O Passo 1 (Recebimento) e sempre
 * automatico (ver {@code FluxoProcessoService}), por isso o antigo endpoint
 * {@code POST /{id}/recebimento} (upload de SOLICITACAO_RECEBIDA + geracao
 * da CAPA_PROCESSO) foi removido - nao existe mais nenhum processo real que
 * precise dele.
 */
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
    private final SolicitacaoOnlineService solicitacaoOnlineService;
    private final SolicitacaoOnlineRepository solicitacaoOnlineRepository;
    private final MensagemSolicitacaoService mensagemService;
    private final UsuarioRepository usuarioRepo;
    private final AnexoRepository anexoRepo;
    private final boolean solicitanteHabilitado;

    public ProcessoDetalheController(ProcessoService processoService,
                                     FluxoProcessoService fluxoService,
                                     EmailTemplateService emailTemplateService,
                                     MembroUrgenciaRenalService membroService,
                                     AnexoStorageService anexoStorage,
                                     AuditoriaService auditoria,
                                     GeminiService geminiService,
                                     ConflitoEquipeMatcher conflitoEquipeMatcher,
                                     SolicitacaoOnlineService solicitacaoOnlineService,
                                     SolicitacaoOnlineRepository solicitacaoOnlineRepository,
                                     MensagemSolicitacaoService mensagemService,
                                     UsuarioRepository usuarioRepo,
                                     AnexoRepository anexoRepo,
                                     @Value("${app.solicitante.habilitado:true}") boolean solicitanteHabilitado) {
        this.processoService = processoService;
        this.fluxoService = fluxoService;
        this.emailTemplateService = emailTemplateService;
        this.membroService = membroService;
        this.anexoStorage = anexoStorage;
        this.auditoria = auditoria;
        this.geminiService = geminiService;
        this.conflitoEquipeMatcher = conflitoEquipeMatcher;
        this.solicitacaoOnlineService = solicitacaoOnlineService;
        this.solicitacaoOnlineRepository = solicitacaoOnlineRepository;
        this.mensagemService = mensagemService;
        this.usuarioRepo = usuarioRepo;
        this.anexoRepo = anexoRepo;
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
    public String novo(@RequestParam(required = false) Long origemSolicitacaoOnlineId, Model model,
                        RedirectAttributes ra) {
        // Desde 2026-07-27, cada processo tem que vir de uma SolicitacaoOnline
        // convertida pelo Portal do Solicitante - nao existe mais cadastro
        // manual "do zero". Kill-switch do proprio Portal: se o modulo estiver
        // desligado, nao ha como triar nenhuma solicitacao, logo nao ha como
        // cadastrar processo NENHUM por aqui (a fila de triagem
        // /processos/solicitacoes-online tambem nao esta registrada nesse
        // caso - mensagem direciona para a lista de processos, nao para ela).
        if (!solicitanteHabilitado) {
            ra.addFlashAttribute("erro",
                "O Portal do Solicitante esta desativado. Nao e possivel cadastrar processos "
                    + "enquanto o modulo estiver desligado.");
            return "redirect:/processos";
        }
        if (origemSolicitacaoOnlineId == null) {
            ra.addFlashAttribute("erro",
                "Todo processo deve ser criado a partir de uma solicitacao do Portal do Solicitante.");
            return "redirect:/processos/solicitacoes-online";
        }
        Processo p = new Processo();
        p.setDataSituacaoEspecial(LocalDate.now());
        // Pre-preenche o formulario com os dados que o solicitante ja enviou
        // pelo portal, para o operador nao redigitar tudo. O operador ainda
        // confere os dados, escolhe os 3 avaliadores e digita o numero
        // normalmente - nada do fluxo de cadastro muda por causa disso.
        var s = solicitacaoOnlineService.buscar(origemSolicitacaoOnlineId);
        // Revisar e converter so pode acontecer UMA vez: bloqueia ja aqui (GET,
        // antes de montar o form) se a solicitacao ja foi triada - reforca a
        // mesma checagem feita em salvar() (POST) para quem chega direto por
        // link antigo/aba reaberta/botao voltar do navegador, sem passar pela
        // UI que ja esconde os botoes nesse caso.
        if (s.getStatus() != StatusSolicitacaoOnline.ENVIADA) {
            ra.addFlashAttribute("erro",
                "Esta solicitacao ja foi triada e nao pode ser convertida novamente.");
            return "redirect:/processos/solicitacoes-online/" + origemSolicitacaoOnlineId;
        }
        p.setPacienteNome(s.getPacienteNome());
        p.setPacienteRgct(s.getPacienteRgct());
        p.setSolicitanteEquipe(s.getSolicitanteEquipe());
        p.setSolicitanteEmail(s.getSolicitanteEmail());
        p.setDataSituacaoEspecial(s.getDataSituacaoEspecial());
        p.setObservacoes(s.getJustificativaClinica());
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
        // Mesma exigencia de novo() (GET): todo processo tem que vir de uma
        // SolicitacaoOnline convertida pelo Portal do Solicitante. Kill-switch
        // do modulo bloqueia qualquer cadastro por aqui.
        if (!solicitanteHabilitado) {
            ra.addFlashAttribute("erro",
                "O Portal do Solicitante esta desativado. Nao e possivel cadastrar processos "
                    + "enquanto o modulo estiver desligado.");
            return "redirect:/processos";
        }
        if (origemSolicitacaoOnlineId == null) {
            ra.addFlashAttribute("erro",
                "Todo processo deve ser criado a partir de uma solicitacao do Portal do Solicitante.");
            return "redirect:/processos/solicitacoes-online";
        }
        // Revisar e converter so pode acontecer UMA vez: se a solicitacao ja
        // foi triada (reenvio do form, duplo clique, aba antiga reaberta),
        // rejeita ANTES de cadastrar o Processo - checar so depois (como era
        // antes) criava um Processo duplicado de verdade e so avisava, sem
        // desfazer nada, porque a excecao chegava tarde demais.
        var origem = solicitacaoOnlineService.buscar(origemSolicitacaoOnlineId);
        if (origem.getStatus() != StatusSolicitacaoOnline.ENVIADA) {
            ra.addFlashAttribute("erro",
                "Esta solicitacao ja foi triada e nao pode ser convertida novamente.");
            return "redirect:/processos/solicitacoes-online/" + origemSolicitacaoOnlineId;
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
                    "Data de solicitacao da urgencia renal fora do intervalo esperado (verifique o ano digitado).");
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
            "Processo " + salvo.getNumero() + " - " + Iniciais.de(salvo.getPacienteNome()));
        // Fecha o vinculo com a solicitacao online de origem - copia os
        // documentos clinicos anexados pelo solicitante para o processo e
        // marca a solicitacao como CONVERTIDA. Feito DEPOIS do cadastro ja
        // ter tido sucesso; se falhar aqui, o processo continua valido (so a
        // solicitacao de origem fica sem o vinculo automatico, corrigivel
        // manualmente).
        try {
            solicitacaoOnlineService.converter(origemSolicitacaoOnlineId, salvo);
            auditoria.registrar("SOLICITACAO_ONLINE_CONVERTIDA",
                "Solicitacao " + origemSolicitacaoOnlineId + " -> Processo " + salvo.getNumero());
        } catch (IllegalStateException | IllegalArgumentException e) {
            ra.addFlashAttribute("aviso",
                "Processo cadastrado, mas houve falha ao vincular a solicitacao online de origem: "
                    + e.getMessage());
        }
        ra.addFlashAttribute("msg", "Processo " + salvo.getNumero() + " cadastrado.");
        return "redirect:/processos/" + salvo.getId();
    }

    @GetMapping("/{id}")
    // Usa o @Transactional (read-write) padrao da classe: desde a correcao do
    // bug do marcarComoLidas (2026-07-28) esta rota tambem escreve (marca
    // mensagens do solicitante como lidas), entao nao pode mais ser readOnly.
    public String detalhe(@PathVariable Long id, Model model, Principal principal) {
        Processo p = processoService.buscar(id);
        model.addAttribute("processo", p);
        // Evita notificacao duplicada: esta tela ja tem seu proprio poll de chat
        // (chat-solicitacao.js), entao o poll GLOBAL da navbar (layout.html) fica
        // desligado aqui.
        model.addAttribute("chatAtivoNestaTela", true);
        // Nome da pasta que o operador vera ao descompactar o dossie
        // (botao "Baixar processo completo (ZIP)" no card de Atalhos).
        model.addAttribute("nomePastaExportacao", ExportacaoProcessoService.nomePasta(p));
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
        // Todo processo nasce de uma SolicitacaoOnline convertida pelo Portal
        // do Solicitante (desde 2026-07-27) - usado so para o link "Ver
        // solicitacao original" na tela de detalhe. Ver FluxoProcessoService.veioDoPortal.
        boolean processoVeioDoPortal = fluxoService.veioDoPortal(p);
        model.addAttribute("processoVeioDoPortal", processoVeioDoPortal); // Mantem para o link "Ver solicitacao original"
        if (processoVeioDoPortal) {
            // Carrega o ID da solicitacao de origem e o chat. Mesmo que todo processo
            // deva ter uma origem, uma verificacao de nulidade aqui protege contra
            // inconsistencias de dados (ex.: solicitacao de origem deletada).
            Optional<Long> solicitacaoOrigemIdOpt = solicitacaoOnlineRepository.findIdByProcessoGeradoId(p.getId());
            if (solicitacaoOrigemIdOpt.isPresent()) {
                Long solicitacaoOrigemId = solicitacaoOrigemIdOpt.get();
                model.addAttribute("solicitacaoOnlineOrigemId", solicitacaoOrigemId);
                java.util.List<MensagemSolicitacao> mensagens = mensagemService.listarPorSolicitacao(solicitacaoOrigemId);
                model.addAttribute("mensagens", mensagens);
                long msgNaoLidas = mensagens.stream()
                    .filter(m -> !m.isLida() && m.getRemetente() == MensagemSolicitacao.RemetenteMensagem.SOLICITANTE)
                    .count();
                model.addAttribute("msgNaoLidas", msgNaoLidas);
                // Bug corrigido em 2026-07-28: faltava marcar como lidas aqui (unica
                // das 3 telas de chat que nao chamava isso) - o badge/notificacao
                // ficava preso pra sempre pra quem so usa esta tela. Ver CLAUDE.md.
                Usuario operadorLogado = usuarioRepo.findByUsername(principal.getName()).orElse(null);
                if (operadorLogado != null) {
                    mensagemService.marcarComoLidas(solicitacaoOrigemId,
                        MensagemSolicitacao.RemetenteMensagem.SOLICITANTE, operadorLogado.getId());
                }
            } else {
                model.addAttribute("solicitacaoOnlineOrigemId", null);
            }
        } else {
            model.addAttribute("solicitacaoOnlineOrigemId", null);
        }
        // Documentos clinicos anonimizados que serao consolidados no PDF dos avaliadores
        java.util.List<Anexo> documentosClinicos = p.getAnexos().stream()
            .filter(a -> a.getTipo() == TipoAnexo.DOCUMENTO_CLINICO_AVALIADOR)
            .collect(java.util.stream.Collectors.toList());
        model.addAttribute("documentosClinicos", documentosClinicos);
        // TRAVA DE ANONIMIZACAO: documentos que vieram do Portal do Solicitante e
        // ainda NAO foram revisados. Ficam numa lista separada justamente para a
        // aba Envio deixar obvio que eles NAO serao enviados aos avaliadores
        // enquanto o operador nao confirmar a anonimizacao.
        model.addAttribute("documentosPendentesAnonimizacao",
            p.getAnexos().stream()
                .filter(a -> a.getTipo() == TipoAnexo.DOCUMENTO_PORTAL_NAO_ANONIMIZADO)
                .sorted(java.util.Comparator.comparing(Anexo::getDataUpload))
                .toList());
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
        // Anexos de informacao complementar ja recebidos (via e-mail lancado pelo
        // operador OU enviados diretamente pelo solicitante no Portal do Solicitante).
        model.addAttribute("anexosInfoComplementar",
            p.getAnexos().stream()
                .filter(a -> a.getTipo() == TipoAnexo.INFO_COMPLEMENTAR)
                .sorted(java.util.Comparator.comparing(Anexo::getDataUpload))
                .toList());

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

        // Previa do e-mail de resposta (deferido/indeferido) para exibir
        // na aba Finalizacao antes do envio automatico.
        if (p.getStatus() == StatusProcesso.DEFERIDO) {
            model.addAttribute("emailRespostaPreview", emailTemplateService.emailDeferido(p));
        } else if (p.getStatus() == StatusProcesso.INDEFERIDO) {
            model.addAttribute("emailRespostaPreview", emailTemplateService.emailIndeferido(p));
        }

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
     * TRAVA DE ANONIMIZACAO (Passo 2 - Envio): promove um documento que veio do
     * Portal do Solicitante ({@code DOCUMENTO_PORTAL_NAO_ANONIMIZADO}, staging,
     * que nunca entra no PDF dos avaliadores) para
     * {@code DOCUMENTO_CLINICO_AVALIADOR}, tornando-o elegivel ao envio.
     *
     * <p>E o unico caminho de promocao, e exige a confirmacao explicita do
     * operador ("Confirmo que este documento foi anonimizado") mais o registro
     * em auditoria de QUEM confirmou e QUAL anexo - esse log e o registro de que
     * a revisao humana aconteceu. Sem isso, o documento original do solicitante
     * (com o nome completo do paciente no corpo do laudo) chegaria aos 3 medicos
     * e quebraria a regra de imparcialidade sem deixar rastro.
     *
     * <p>O operador tambem pode ignorar a promocao e simplesmente subir um
     * arquivo ja anonimizado por {@code POST /{id}/documento-clinico}, que
     * continua entrando direto como {@code DOCUMENTO_CLINICO_AVALIADOR}.
     */
    @PostMapping("/{id}/documento-clinico/{anexoId}/confirmar-anonimizacao")
    public String confirmarAnonimizacao(@PathVariable Long id,
                                        @PathVariable Long anexoId,
                                        @RequestParam(required = false, defaultValue = "false") boolean confirmo,
                                        Principal principal,
                                        RedirectAttributes ra) {
        Processo p = processoService.buscar(id);
        if (bloqueadoPorEncerrado(p, ra)) {
            return "redirect:/processos/" + id + "#envio";
        }
        if (!confirmo) {
            ra.addFlashAttribute("erro",
                "Marque a confirmacao de que o documento foi anonimizado (nome do paciente removido "
                    + "do corpo) antes de libera-lo para os avaliadores.");
            return "redirect:/processos/" + id + "#envio";
        }
        Anexo anexo = p.getAnexos().stream()
            .filter(a -> a.getId() != null && a.getId().equals(anexoId))
            .findFirst()
            .orElse(null);
        if (anexo == null) {
            // Nunca serve um anexo de outro processo: a busca e feita dentro da
            // colecao do proprio processo (mesma postura anti-IDOR das outras telas).
            ra.addFlashAttribute("erro", "Documento nao encontrado neste processo.");
            return "redirect:/processos/" + id + "#envio";
        }
        if (anexo.getTipo() != TipoAnexo.DOCUMENTO_PORTAL_NAO_ANONIMIZADO) {
            ra.addFlashAttribute("erro",
                "Este documento nao esta pendente de anonimizacao.");
            return "redirect:/processos/" + id + "#envio";
        }
        String quem = principal != null ? principal.getName() : "desconhecido";
        anexo.setTipo(TipoAnexo.DOCUMENTO_CLINICO_AVALIADOR);
        anexo.setDescricao("Documento do Portal do Solicitante com anonimizacao CONFIRMADA por "
            + quem + " em " + java.time.LocalDate.now().format(
                java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")));
        anexoRepo.save(anexo);
        auditoria.registrar("ANONIMIZACAO_CONFIRMADA",
            "Processo " + p.getNumero() + " - anexo id " + anexoId + " (" + anexo.getNomeArquivo()
                + ") liberado para os avaliadores por " + quem);
        ra.addFlashAttribute("msg", "Anonimizacao confirmada: \"" + anexo.getNomeArquivo()
            + "\" agora entra no PDF enviado aos avaliadores.");
        return "redirect:/processos/" + id + "#envio";
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
    @PostMapping("/{id}/mensagem")
    public String enviarMensagem(@PathVariable Long id, @RequestParam String texto,
                                 Principal principal, RedirectAttributes ra) {
        Processo p = processoService.buscar(id);
        Long solicitacaoOrigemId = solicitacaoOnlineRepository.findIdByProcessoGeradoId(p.getId()).orElse(null);
        if (solicitacaoOrigemId == null) {
            ra.addFlashAttribute("erro", "Este processo nao possui solicitacao de origem vinculada.");
            return "redirect:/processos/" + id;
        }
        if (texto == null || texto.isBlank()) {
            ra.addFlashAttribute("erro", "A mensagem nao pode estar em branco.");
            return "redirect:/processos/" + id;
        }
        SolicitacaoOnline s = solicitacaoOnlineService.buscar(solicitacaoOrigemId);
        Usuario operador = usuarioRepo.findByUsername(principal.getName())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        mensagemService.enviar(s, texto, MensagemSolicitacao.RemetenteMensagem.OPERADOR, operador.getId());
        auditoria.registrar("MENSAGEM_OPERADOR_ENVIADA",
            "Processo " + p.getNumero() + " - resposta do operador " + operador.getUsername());
        return "redirect:/processos/" + id;
    }

    @PostMapping("/{id}/mensagem/{mensagemId}/apagar")
    public String apagarMensagem(@PathVariable Long id, @PathVariable Long mensagemId,
                                  Principal principal, RedirectAttributes ra) {
        try {
            Usuario operador = usuarioRepo.findByUsername(principal.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
            mensagemService.apagar(mensagemId, operador.getId(), MensagemSolicitacao.RemetenteMensagem.OPERADOR);
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("erro", e.getMessage());
        }
        return "redirect:/processos/" + id;
    }

    /** Polling do chat (AJAX) - equivalente ao usado nas outras 2 telas de chat. */
    @GetMapping("/{id}/mensagens")
    @ResponseBody
    public java.util.Map<String, Object> mensagensJson(@PathVariable Long id, Principal principal) {
        Processo p = processoService.buscar(id);
        Long solicitacaoOrigemId = solicitacaoOnlineRepository.findIdByProcessoGeradoId(p.getId()).orElse(null);
        java.util.Map<String, Object> resp = new java.util.LinkedHashMap<>();
        if (solicitacaoOrigemId == null) {
            resp.put("mensagens", java.util.List.of());
            resp.put("podeEnviar", false);
            return resp;
        }
        Usuario operador = usuarioRepo.findByUsername(principal.getName())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        mensagemService.marcarComoLidas(solicitacaoOrigemId, MensagemSolicitacao.RemetenteMensagem.SOLICITANTE, operador.getId());
        resp.put("mensagens", mensagemService.paraChat(
            solicitacaoOrigemId, MensagemSolicitacao.RemetenteMensagem.OPERADOR, operador.getId(), "Voce", "Solicitante"));
        resp.put("podeEnviar", true);
        return resp;
    }

    @PostMapping("/{id}/mensagem/ajax")
    @ResponseBody
    public ResponseEntity<java.util.Map<String, Object>> enviarMensagemAjax(@PathVariable Long id,
            @RequestParam String texto, Principal principal) {
        Processo p = processoService.buscar(id);
        Long solicitacaoOrigemId = solicitacaoOnlineRepository.findIdByProcessoGeradoId(p.getId()).orElse(null);
        if (solicitacaoOrigemId == null) {
            return ResponseEntity.badRequest().body(java.util.Map.of("erro",
                "Este processo nao possui solicitacao de origem vinculada."));
        }
        if (texto == null || texto.isBlank()) {
            return ResponseEntity.badRequest().body(java.util.Map.of("erro", "A mensagem nao pode estar em branco."));
        }
        SolicitacaoOnline s = solicitacaoOnlineService.buscar(solicitacaoOrigemId);
        Usuario operador = usuarioRepo.findByUsername(principal.getName())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        mensagemService.enviar(s, texto, MensagemSolicitacao.RemetenteMensagem.OPERADOR, operador.getId());
        auditoria.registrar("MENSAGEM_OPERADOR_ENVIADA",
            "Processo " + p.getNumero() + " - resposta do operador " + operador.getUsername());
        return ResponseEntity.ok(java.util.Map.of("ok", true));
    }

    @PostMapping("/{id}/mensagem/{mensagemId}/apagar/ajax")
    @ResponseBody
    public ResponseEntity<java.util.Map<String, Object>> apagarMensagemAjax(@PathVariable Long id,
            @PathVariable Long mensagemId, Principal principal) {
        try {
            Usuario operador = usuarioRepo.findByUsername(principal.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
            mensagemService.apagar(mensagemId, operador.getId(), MensagemSolicitacao.RemetenteMensagem.OPERADOR);
            return ResponseEntity.ok(java.util.Map.of("ok", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("erro", e.getMessage()));
        }
    }

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
