package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.*;
import br.gov.saude.sgpur.service.AnexoStorageService;
import br.gov.saude.sgpur.service.AuditoriaService;
import br.gov.saude.sgpur.service.DecisaoFinalService;
import br.gov.saude.sgpur.service.EmailSenderService;
import br.gov.saude.sgpur.service.EmailTemplate;
import br.gov.saude.sgpur.service.EmailTemplateService;
import br.gov.saude.sgpur.service.GeminiService;
import br.gov.saude.sgpur.service.ProcessoService;
import br.gov.saude.sgpur.service.ProcessoValidator;
import br.gov.saude.sgpur.service.RegistroEnvioService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Passos 2 a 4 do fluxo: envio aos avaliadores, respostas/pareceres, decisao
 * final e a pausa "Solicita informacao". Inclui o disparo manual de e-mails
 * (lembretes e textos prontos) e a assistencia por IA ligada a decisao.
 */
@Controller
@RequestMapping("/processos")
@Transactional
public class ProcessoDecisaoController {

    private final ProcessoService processoService;
    private final ProcessoValidator validator;
    private final DecisaoFinalService decisaoFinalService;
    private final RegistroEnvioService registroEnvioService;
    private final EmailTemplateService emailTemplateService;
    private final EmailSenderService emailSenderService;
    private final AnexoStorageService anexoStorage;
    private final AuditoriaService auditoria;
    private final GeminiService geminiService;

    public ProcessoDecisaoController(ProcessoService processoService,
                                     ProcessoValidator validator,
                                     DecisaoFinalService decisaoFinalService,
                                     RegistroEnvioService registroEnvioService,
                                     EmailTemplateService emailTemplateService,
                                     EmailSenderService emailSenderService,
                                     AnexoStorageService anexoStorage,
                                     AuditoriaService auditoria,
                                     GeminiService geminiService) {
        this.processoService = processoService;
        this.validator = validator;
        this.decisaoFinalService = decisaoFinalService;
        this.registroEnvioService = registroEnvioService;
        this.emailTemplateService = emailTemplateService;
        this.emailSenderService = emailSenderService;
        this.anexoStorage = anexoStorage;
        this.auditoria = auditoria;
        this.geminiService = geminiService;
    }

    /** Salva os pareceres (envio/resultado/datas) editados na tela de detalhe. */
    @PostMapping("/{id}/pareceres")
    public String salvarPareceres(@PathVariable Long id,
                                  @RequestParam(required = false) java.util.List<Long> parecerId,
                                  @RequestParam(required = false) java.util.List<String> resultado,
                                  @RequestParam(required = false) java.util.List<String> dataEnvio,
                                  RedirectAttributes ra) {
        Processo p = processoService.buscar(id);
        if (bloqueadoPorEncerrado(p, ra)) {
            return "redirect:/processos/" + id + "#respostas";
        }
        if (parecerId != null) {
            for (int i = 0; i < parecerId.size(); i++) {
                Long pid = parecerId.get(i);
                // Distingue "indice ausente" (array mais curto que parecerId - cliente
                // parcial/adulterado) de "indice presente mas em branco" (limpeza
                // explicita, mesmo comportamento de sempre do form original que sempre
                // envia todos os pareceres). So o segundo caso deve zerar o campo -
                // o primeiro deve DEIXAR O VALOR ATUAL INTOCADO, para nao apagar
                // resultado/dataEnvio ja gravados so porque o array veio incompleto.
                boolean resPresente = resultado != null && i < resultado.size();
                boolean envPresente = dataEnvio != null && i < dataEnvio.size();
                String res = resPresente ? resultado.get(i) : null;
                String env = envPresente ? dataEnvio.get(i) : null;
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
                        // Parses defensivos: valores adulterados (fora do <select>/
                        // date picker) geram uma mensagem de negocio clara em vez de
                        // "Registro nao encontrado" (IllegalArgumentException generica).
                        if (envPresente) {
                            if (env == null || env.isBlank()) {
                                par.setDataEnvio(null);
                            } else {
                                try {
                                    par.setDataEnvio(LocalDate.parse(env));
                                } catch (java.time.format.DateTimeParseException e) {
                                    throw new IllegalStateException("Data de envio invalida: " + env);
                                }
                            }
                        }
                        if (resPresente) {
                            if (res == null || res.isBlank()) {
                                par.setResultado(null);
                            } else {
                                ResultadoParecer parsed;
                                try {
                                    parsed = ResultadoParecer.valueOf(res);
                                } catch (IllegalArgumentException e) {
                                    throw new IllegalStateException("Parecer invalido: " + res);
                                }
                                if (!parsed.isVotoValido()) {
                                    throw new IllegalStateException("Parecer invalido: " + res);
                                }
                                par.setResultado(parsed);
                                if (par.getDataResposta() == null) {
                                    par.setDataResposta(LocalDate.now());
                                }
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
        if (bloqueadoPorEncerrado(p, ra)) {
            return "redirect:/processos/" + id + "#respostas";
        }
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
        if (bloqueadoPorEncerrado(p, ra)) {
            return "redirect:/processos/" + id + "#respostas";
        }
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
     * Etapa 2 (Envio): registra a data de envio de hoje para os 3 medicos e
     * gera o PDF consolidado (documentos clinicos anonimizados + cabecalho
     * carimbado). Sem documento clinico PDF nao ha o que enviar: bloqueia.
     */
    @PostMapping("/{id}/registrar-envio")
    public String registrarEnvio(@PathVariable Long id,
                                 RedirectAttributes ra) {
        Processo p = processoService.buscar(id);
        if (bloqueadoPorEncerrado(p, ra)) {
            return "redirect:/processos/" + id + "#envio";
        }
        RegistroEnvioService.RegistroEnvioResultado resultado = registroEnvioService.registrar(id);
        if (!resultado.ok()) {
            ra.addFlashAttribute("erro", resultado.mensagemErro());
            return "redirect:/processos/" + id + "#envio";
        }
        List<String> avisosCombinados = new ArrayList<>();
        if (!resultado.avisos().isEmpty()) {
            avisosCombinados.add("Estes documentos clinicos ficaram de fora do PDF consolidado: "
                + String.join(", ", resultado.avisos()) + ".");
        }
        if (!resultado.avisosEmail().isEmpty()) {
            avisosCombinados.add(String.join(" ", resultado.avisosEmail()));
        }
        if (!avisosCombinados.isEmpty()) {
            ra.addFlashAttribute("aviso", String.join(" ", avisosCombinados));
        }
        ra.addFlashAttribute("msg", resultado.mensagemSucesso());
        return "redirect:/processos/" + id + "#envio";
    }

    /**
     * Anexa o comprovante de envio (e-mail) aos avaliadores, separadamente
     * do registro de envio. Permite que o operador anexe o comprovante antes
     * ou depois de registrar o envio.
     */
    @PostMapping("/{id}/comprovante-envio-avaliadores")
    public String anexarComprovanteEnvioAvaliadores(@PathVariable Long id,
                                                     @RequestParam("arquivo") MultipartFile arquivo,
                                                     RedirectAttributes ra) {
        if (arquivo == null || arquivo.isEmpty()) {
            ra.addFlashAttribute("erro", "Selecione um arquivo para anexar.");
            return "redirect:/processos/" + id + "#envio";
        }
        Processo p = processoService.buscar(id);
        if (bloqueadoPorEncerrado(p, ra)) {
            return "redirect:/processos/" + id + "#envio";
        }
        try {
            // Salva o novo primeiro, so remove o antigo depois de confirmado
            // o sucesso - evita o processo ficar sem nenhum comprovante de
            // envio se o save() falhar entre o remover e o salvar.
            Anexo novo = anexoStorage.salvar(p, TipoAnexo.EMAIL_ENVIADO_AVALIADORES,
                "Comprovante de envio aos avaliadores", arquivo);
            anexoStorage.removerAntigosDoTipo(id, TipoAnexo.EMAIL_ENVIADO_AVALIADORES, novo.getId());
            auditoria.registrar("ANEXO_ADICIONADO",
                "Processo " + p.getNumero() + " - " + TipoAnexo.EMAIL_ENVIADO_AVALIADORES.getDescricao());
            ra.addFlashAttribute("msg", "Comprovante de envio anexado.");
        } catch (IllegalArgumentException | IOException e) {
            ra.addFlashAttribute("erro", "Falha ao anexar o comprovante: " + e.getMessage());
        }
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
        if (bloqueadoPorEncerrado(p, ra)) {
            return "redirect:/processos/" + id + "#envio";
        }
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
                          HttpServletRequest request,
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
        Processo atual = processoService.buscar(id);
        if (bloqueadoPorEncerrado(atual, ra)) {
            return "redirect:/processos/" + id;
        }
        // Regras de negocio centralizadas em ProcessoValidator (mesmas mensagens
        // do servico). A ancora do redirect distingue pausa/anexos (#respostas)
        // das demais (topo), por isso os grupos sao consultados separadamente.
        var pausa = validator.validarPausaDecisao(atual, decisao);
        if (pausa.isPresent()) {
            ra.addFlashAttribute("erro", pausa.get());
            return "redirect:/processos/" + id + "#respostas";
        }
        var votos = validator.validarContagemVotos(atual, decisao);
        if (votos.isPresent()) {
            ra.addFlashAttribute("erro", votos.get());
            return "redirect:/processos/" + id;
        }
        var anexos = validator.validarAnexosResposta(atual, decisao);
        if (anexos.isPresent()) {
            ra.addFlashAttribute("erro", anexos.get());
            return "redirect:/processos/" + id + "#respostas";
        }
        Processo p = processoService.decidir(id, decisao, motivoIndeferimento);
        try { decisaoFinalService.gerarDocumentos(p); }
        catch (IllegalStateException e) { ra.addFlashAttribute("erro", e.getMessage()); }
        auditoria.registrar("PROCESSO_DECIDIDO",
            "Processo " + p.getNumero() + " - " + decisao.getDescricao(),
            request.getRemoteAddr());
        ra.addFlashAttribute("msg", "Decisao registrada: " + decisao.getDescricao());
        return "redirect:/processos/" + id;
    }

    /**
     * Sugere, via IA, um texto para o motivo do indeferimento com base nas
     * justificativas dos pareceres desfavoraveis. O operador revisa/edita
     * antes de registrar a decisao - a IA nao decide nada, so redige.
     */
    @PostMapping("/{id}/sugestao-motivo")
    @ResponseBody
    @Transactional(readOnly = true)
    public IaTextoResponse sugestaoMotivo(@PathVariable Long id) {
        if (!geminiService.isDisponivel()) {
            return IaTextoResponse.erro("Assistencia por IA nao configurada.");
        }
        Processo p = processoService.buscar(id);
        String justificativas = p.getPareceres().stream()
            .filter(par -> par.getResultado() == ResultadoParecer.NAO_FAVORAVEL)
            .map(Parecer::getJustificativa)
            .filter(j -> j != null && !j.isBlank())
            .collect(java.util.stream.Collectors.joining("\n---\n"));
        if (justificativas.isBlank()) {
            return IaTextoResponse.erro("Nenhuma justificativa de parecer desfavoravel encontrada para basear a sugestao.");
        }
        String prompt = "Voce e um assistente administrativo de um orgao publico de saude do Brasil. "
            + "Com base nas justificativas tecnicas abaixo, dadas por medicos avaliadores que "
            + "consideraram um pedido de urgencia renal desfavoravel, redija um texto formal, "
            + "objetivo e em portugues do Brasil para o campo \"motivo do indeferimento\" de um "
            + "oficio administrativo. Nao invente informacoes que nao estejam nas justificativas. "
            + "Responda apenas com o texto do motivo, sem introducao nem explicacoes.\n\n"
            + "Justificativas dos avaliadores:\n" + justificativas;
        return geminiService.perguntar(prompt)
            .map(IaTextoResponse::sucesso)
            .orElseGet(() -> IaTextoResponse.erro("Falha ao consultar a IA. Tente novamente."));
    }

    /**
     * Faz upload do e-mail de resposta de um avaliador especifico e opcionalmente
     * registra o resultado (parecer) em uma unica acao. Se resultado for informado,
     * o parecer e atualizado de uma vez — dispensando o form separado de pareceres.
     */
    @PostMapping("/{id}/resposta-avaliador")
    public String respostaAvaliador(@PathVariable Long id,
                                    @RequestParam Long parecerId,
                                    @RequestParam(value = "arquivo", required = false) MultipartFile arquivo,
                                    @RequestParam(required = false) String descricao,
                                    @RequestParam(required = false) String resultado,
                                    RedirectAttributes ra) {
        Processo p = processoService.buscar(id);
        if (bloqueadoPorEncerrado(p, ra)) {
            return "redirect:/processos/" + id + "#respostas";
        }
        Parecer parecer = processoService.buscarParecer(id, parecerId)
            .orElseThrow(() -> new IllegalArgumentException("Parecer nao encontrado neste processo: " + parecerId));
        if (parecer.getOrigem() == OrigemParecer.AVALIADOR_SISTEMA) {
            ra.addFlashAttribute("erro",
                "Nao e possivel anexar resposta de um avaliador que votou pelo portal (nao-repudio).");
            return "redirect:/processos/" + id + "#respostas";
        }
        // Parecer ainda sem resultado: o resultado e obrigatorio JUNTO com o anexo.
        // Sem essa checagem, um anexo sem resultado selecionado salvava o arquivo e
        // deixava o parecer preso sem resultado - a tela some com os dois botoes
        // (upload exige !pareceresComResposta) e nao ha mais como registrar o parecer.
        // Faz o parse ANTES de salvar o anexo: um valor invalido nao pode deixar o
        // anexo salvo com o parecer ainda sem resultado.
        boolean resultadoInformado = resultado != null && !resultado.isBlank();
        if (parecer.getResultado() == null && !resultadoInformado) {
            ra.addFlashAttribute("erro",
                "Selecione o parecer (Favoravel/Nao favoravel/Solicita informacao) antes de anexar a resposta.");
            return "redirect:/processos/" + id + "#respostas";
        }
        ResultadoParecer resultadoParseado = null;
        if (resultadoInformado) {
            try {
                resultadoParseado = ResultadoParecer.valueOf(resultado);
            } catch (IllegalArgumentException e) {
                ra.addFlashAttribute("erro", "Parecer invalido: " + resultado);
                return "redirect:/processos/" + id + "#respostas";
            }
            if (!resultadoParseado.isVotoValido()) {
                ra.addFlashAttribute("erro", "Parecer invalido: " + resultado);
                return "redirect:/processos/" + id + "#respostas";
            }
        }
        // Arquivo e opcional apenas quando o parecer JA tem um anexo de resposta
        // (caso de recuperacao: operador so precisa completar o resultado que
        // ficou pendente). Sem anexo previo, o arquivo continua obrigatorio.
        boolean jaTemAnexoResposta = p.getAnexos().stream()
            .anyMatch(a -> a.getTipo() == TipoAnexo.RESPOSTA_AVALIADOR
                && a.getParecer() != null && a.getParecer().getId().equals(parecerId));
        boolean arquivoEnviado = arquivo != null && !arquivo.isEmpty();
        if (!arquivoEnviado && !jaTemAnexoResposta) {
            ra.addFlashAttribute("erro", "Selecione o arquivo da resposta do avaliador.");
            return "redirect:/processos/" + id + "#respostas";
        }
        try {
            if (arquivoEnviado) {
                String desc = (descricao != null && !descricao.isBlank())
                    ? descricao
                    : "Resposta de " + parecer.getMembro().getNome();
                anexoStorage.salvarRespostaAvaliador(p, parecer, desc, arquivo);
                auditoria.registrar("ANEXO_ADICIONADO",
                    "Processo " + p.getNumero() + " - Resposta de " + parecer.getMembro().getNome());
            }
        } catch (IllegalArgumentException | IOException e) {
            ra.addFlashAttribute("erro", "Falha ao anexar resposta: " + e.getMessage());
            return "redirect:/processos/" + id + "#respostas";
        }
        // Se resultado foi informado, atualiza o parecer de uma vez
        if (resultadoInformado) {
            parecer.setResultado(resultadoParseado);
            if (parecer.getDataResposta() == null) {
                parecer.setDataResposta(LocalDate.now());
            }
            processoService.salvar(p);
            processoService.atualizarStatusPorPareceres(id);
            Processo pDecidido = processoService.tentarDecisaoAutomatica(id);
            if (pDecidido.getStatus().isFinalizado()) {
                try { decisaoFinalService.gerarDocumentos(pDecidido); }
                catch (IllegalStateException e) { ra.addFlashAttribute("erro", e.getMessage()); }
                ra.addFlashAttribute("msg", "Resposta e parecer registrados. Decisao automatica: "
                    + pDecidido.getStatus().getDescricao() + ".");
                return "redirect:/processos/" + id;
            }
            ra.addFlashAttribute("msg", "Resposta de " + parecer.getMembro().getNome()
                + " anexada e parecer registrado.");
        } else {
            // So o anexo foi enviado (parecer ja tinha resultado, faltava o
            // comprovante). Subir esse anexo pode satisfazer a ultima
            // pre-condicao pendente (pareceresRecebidosSemAnexo) e liberar a
            // decisao por maioria - por isso reavalia igual aos demais
            // call-sites (salvarPareceres/portal), em vez de so exibir a mensagem.
            processoService.atualizarStatusPorPareceres(id);
            Processo pDecidido = processoService.tentarDecisaoAutomatica(id);
            if (pDecidido.getStatus().isFinalizado()) {
                try { decisaoFinalService.gerarDocumentos(pDecidido); }
                catch (IllegalStateException e) { ra.addFlashAttribute("erro", e.getMessage()); }
                ra.addFlashAttribute("msg", "Resposta de " + parecer.getMembro().getNome()
                    + " anexada. Decisao automatica: " + pDecidido.getStatus().getDescricao() + ".");
                return "redirect:/processos/" + id;
            }
            ra.addFlashAttribute("msg", "Resposta de " + parecer.getMembro().getNome() + " anexada.");
        }
        return "redirect:/processos/" + id + "#respostas";
    }

    /**
     * Dispara manualmente um lembrete por e-mail a UM avaliador com parecer
     * pendente. Nunca automatico - sempre um clique explicito do operador.
     */
    @PostMapping("/{id}/lembrete-avaliador")
    @ResponseBody
    // Sem readOnly: grava auditoria (INSERT) apos o envio - herda o
    // @Transactional (leitura-escrita) da classe.
    public AcaoResponse lembreteAvaliador(@PathVariable Long id, @RequestParam Long parecerId) {
        Processo p = processoService.buscar(id);
        if (validator.edicaoBloqueada(p)) {
            return AcaoResponse.erro(ProcessoValidator.MSG_ENCERRADO);
        }
        Parecer parecer = processoService.buscarParecer(id, parecerId).orElse(null);
        if (parecer == null) {
            return AcaoResponse.erro("Parecer nao encontrado neste processo.");
        }
        if (parecer.getResultado() != null) {
            return AcaoResponse.erro("Este avaliador ja registrou o parecer.");
        }
        MembroUrgenciaRenal membro = parecer.getMembro();
        if (membro.getEmail() == null || membro.getEmail().isBlank()) {
            return AcaoResponse.erro("Avaliador sem e-mail cadastrado: " + membro.getNome() + ".");
        }
        EmailTemplate template = emailTemplateService.emailLembreteAvaliador(p, membro);
        boolean ok = emailSenderService.enviar(membro.getEmail(), template.assunto(), template.corpo());
        if (ok) {
            auditoria.registrar("LEMBRETE_AVALIADOR_ENVIADO",
                "Processo " + p.getNumero() + " - " + membro.getNome());
            return AcaoResponse.sucesso("Lembrete enviado para " + membro.getNome() + ".");
        }
        auditoria.registrar("LEMBRETE_AVALIADOR_FALHA",
            "Processo " + p.getNumero() + " - " + membro.getNome());
        return AcaoResponse.erro("Falha ao enviar o e-mail. Verifique a configuracao de SMTP.");
    }

    /**
     * Dispara manualmente um lembrete por e-mail a TODOS os avaliadores com
     * parecer pendente neste processo (envio em lote, ainda assim manual).
     */
    @PostMapping("/{id}/lembrete-pendentes")
    @ResponseBody
    // Sem readOnly: grava auditoria (INSERT) por avaliador - herda o
    // @Transactional (leitura-escrita) da classe.
    public AcaoResponse lembretePendentes(@PathVariable Long id) {
        Processo p = processoService.buscar(id);
        if (validator.edicaoBloqueada(p)) {
            return AcaoResponse.erro(ProcessoValidator.MSG_ENCERRADO);
        }
        var pendentes = processoService.pareceresPendentesComEmail(id);
        if (pendentes.isEmpty()) {
            return AcaoResponse.erro("Nao ha avaliadores com parecer pendente neste processo.");
        }
        int enviados = 0, falhas = 0, semEmail = 0;
        for (Parecer parecer : pendentes) {
            MembroUrgenciaRenal membro = parecer.getMembro();
            if (membro.getEmail() == null || membro.getEmail().isBlank()) {
                semEmail++;
                continue;
            }
            EmailTemplate template = emailTemplateService.emailLembreteAvaliador(p, membro);
            boolean ok = emailSenderService.enviar(membro.getEmail(), template.assunto(), template.corpo());
            if (ok) {
                enviados++;
                auditoria.registrar("LEMBRETE_AVALIADOR_ENVIADO",
                    "Processo " + p.getNumero() + " - " + membro.getNome());
            } else {
                falhas++;
                auditoria.registrar("LEMBRETE_AVALIADOR_FALHA",
                    "Processo " + p.getNumero() + " - " + membro.getNome());
            }
        }
        String msg = "Lembretes enviados: " + enviados + ". Falhas: " + falhas + ". Sem e-mail: " + semEmail + ".";
        return enviados > 0 ? AcaoResponse.sucesso(msg) : AcaoResponse.erro(msg);
    }

    /**
     * Revisa/melhora, via IA, o corpo de um texto de e-mail pronto (assunto +
     * corpo) exibido na tela de detalhe. O operador confere antes de copiar.
     *
     * ATENCAO (so relevante com app.gemini.enabled=true, desligado por
     * padrao em producao): os e-mails dirigidos ao SOLICITANTE (Deferido,
     * Indeferido, Solicita informacao) levam o NOME COMPLETO do paciente por
     * regra de negocio - o corpo inteiro, incluindo esse nome, e enviado ao
     * Gemini (API externa) para revisao. Nao ha como mitigar sem quebrar a
     * propria funcao (revisar o texto real que sera enviado com nome
     * completo); e uma troca deliberada, condicionada ao operador ligar o
     * recurso de IA conscientemente.
     */
    @PostMapping("/{id}/email/revisar-ia")
    @ResponseBody
    @Transactional(readOnly = true)
    public IaTextoResponse revisarEmailIa(@PathVariable Long id,
                                          @RequestParam String assunto,
                                          @RequestParam String corpo) {
        if (!geminiService.isDisponivel()) {
            return IaTextoResponse.erro("Assistencia por IA nao configurada.");
        }
        String prompt = "Voce e um assistente de redacao de um orgao publico de saude do Brasil. "
            + "Revise o e-mail abaixo (assunto e corpo), mantendo o mesmo idioma (portugues do "
            + "Brasil), o mesmo significado e todos os dados/numeros/nomes citados. Apenas melhore "
            + "clareza, formalidade e correcao gramatical - nao adicione nem remova informacoes. "
            + "Responda apenas com o corpo revisado do e-mail (sem repetir o assunto, sem "
            + "introducao, sem comentarios).\n\n"
            + "Assunto: " + assunto + "\n\nCorpo:\n" + corpo;
        return geminiService.perguntar(prompt)
            .map(IaTextoResponse::sucesso)
            .orElseGet(() -> IaTextoResponse.erro("Falha ao consultar a IA. Tente novamente."));
    }

    /**
     * Dispara manualmente, por e-mail, um dos textos prontos exibidos no
     * accordion "Textos de e-mail prontos". O destinatario e resolvido no
     * servidor pela chave do template (nunca confia em endereco vindo do
     * cliente). Para Deferido/Indeferido, bloqueia o envio se o anexo
     * obrigatorio (comprovante SNT / oficio) ainda nao existir.
     */
    @PostMapping("/{id}/email/enviar")
    @ResponseBody
    // Sem readOnly: grava auditoria (INSERT) apos o envio - herda o
    // @Transactional (leitura-escrita) da classe.
    public AcaoResponse enviarEmailPronto(@PathVariable Long id,
                                          @RequestParam String chave,
                                          @RequestParam String assunto,
                                          @RequestParam String corpo) {
        Processo p = processoService.buscar(id);
        EmailPreparado prep = prepararEmailPronto(p, chave, assunto, corpo);
        if (!prep.ok()) {
            return AcaoResponse.erro(prep.erro());
        }
        // Deferido/Indeferido: o texto do template promete "segue em anexo" o
        // comprovante SNT/oficio - envia de fato com o arquivo anexado quando
        // ele existe (prepararEmailPronto ja localizou), em vez do e-mail
        // simples que deixaria o destinatario sem o documento prometido.
        boolean ok = prep.anexo() != null
            ? emailSenderService.enviarComAnexo(prep.to()[0], prep.assunto(), prep.corpo(),
                anexoStorage.resolverArquivo(prep.anexo()).toFile(), prep.anexo().getNomeArquivo())
            : emailSenderService.enviar(prep.to(), null, prep.assunto(), prep.corpo());
        if (ok) {
            auditoria.registrar("EMAIL_ENVIADO",
                "Processo " + p.getNumero() + " - template " + chave + " -> " + prep.destinatarios());
            return AcaoResponse.sucesso("E-mail enviado para " + prep.destinatarios() + ".");
        }
        auditoria.registrar("EMAIL_ENVIO_FALHA",
            "Processo " + p.getNumero() + " - template " + chave + " -> " + prep.destinatarios());
        return AcaoResponse.erro("Falha ao enviar o e-mail. Verifique a configuracao de SMTP.");
    }

    /**
     * Pre-visualiza, sem enviar, o(s) e-mail(s) que uma acao dispararia -
     * destinatario(s), assunto e corpo exatos. Alimenta o modal de confirmacao:
     * NENHUM e-mail e enviado sem o operador conferir este conteudo antes.
     * A resolucao de destinatarios e as validacoes de anexo obrigatorio usam a
     * mesma logica do envio real, garantindo que o previsto e o enviado coincidam.
     */
    @PostMapping("/{id}/email/preview")
    @ResponseBody
    @Transactional(readOnly = true)
    public EmailPreviewResponse preverEmail(@PathVariable Long id,
                                            @RequestParam String tipo,
                                            @RequestParam(required = false) String chave,
                                            @RequestParam(required = false) String assunto,
                                            @RequestParam(required = false) String corpo,
                                            @RequestParam(required = false) Long parecerId) {
        Processo p = processoService.buscar(id);
        switch (tipo) {
            case "pronto" -> {
                EmailPreparado prep = prepararEmailPronto(p, chave, assunto, corpo);
                if (!prep.ok()) {
                    return EmailPreviewResponse.erro(prep.erro());
                }
                return EmailPreviewResponse.ok(List.of(
                    new EmailPreviewResponse.Mensagem(prep.destinatarios(), prep.assunto(), prep.corpo())));
            }
            case "lembrete-avaliador" -> {
                // parecerId e opcional no request (form pode submeter sem selecao);
                // findById(null) lancaria InvalidDataAccessApiUsageException (500
                // generico) em vez da mensagem amigavel abaixo - checa antes.
                if (parecerId == null) {
                    return EmailPreviewResponse.erro("Parecer nao encontrado neste processo.");
                }
                Parecer parecer = processoService.buscarParecer(id, parecerId).orElse(null);
                if (parecer == null) {
                    return EmailPreviewResponse.erro("Parecer nao encontrado neste processo.");
                }
                if (parecer.getResultado() != null) {
                    return EmailPreviewResponse.erro("Este avaliador ja registrou o parecer.");
                }
                MembroUrgenciaRenal membro = parecer.getMembro();
                if (membro.getEmail() == null || membro.getEmail().isBlank()) {
                    return EmailPreviewResponse.erro("Avaliador sem e-mail cadastrado: " + membro.getNome() + ".");
                }
                EmailTemplate template = emailTemplateService.emailLembreteAvaliador(p, membro);
                return EmailPreviewResponse.ok(List.of(
                    new EmailPreviewResponse.Mensagem(membro.getEmail(), template.assunto(), template.corpo())));
            }
            case "lembrete-pendentes" -> {
                var pendentes = processoService.pareceresPendentesComEmail(id);
                List<EmailPreviewResponse.Mensagem> mensagens = new ArrayList<>();
                for (Parecer parecer : pendentes) {
                    MembroUrgenciaRenal membro = parecer.getMembro();
                    if (membro.getEmail() == null || membro.getEmail().isBlank()) {
                        continue;
                    }
                    EmailTemplate template = emailTemplateService.emailLembreteAvaliador(p, membro);
                    mensagens.add(new EmailPreviewResponse.Mensagem(
                        membro.getEmail(), template.assunto(), template.corpo()));
                }
                if (mensagens.isEmpty()) {
                    return EmailPreviewResponse.erro(
                        "Nao ha avaliadores com parecer pendente e e-mail cadastrado neste processo.");
                }
                return EmailPreviewResponse.ok(mensagens);
            }
            default -> {
                return EmailPreviewResponse.erro("Tipo de pre-visualizacao desconhecido: " + tipo);
            }
        }
    }

    /**
     * Guarda de edicao: se o processo esta encerrado, registra o flash de erro e
     * retorna true (o chamador deve redirecionar sem efetivar a alteracao). Usada
     * nas etapas 1 a 4 e nos lembretes; as etapas 5-6 e os e-mails de resposta ao
     * solicitante NAO usam esta guarda (continuam liberados apos a decisao).
     */
    private boolean bloqueadoPorEncerrado(Processo p, RedirectAttributes ra) {
        if (validator.edicaoBloqueada(p)) {
            ra.addFlashAttribute("erro", ProcessoValidator.MSG_ENCERRADO);
            return true;
        }
        return false;
    }

    /**
     * Resolve destinatarios e valida anexos obrigatorios de um texto de e-mail
     * pronto, sem enviar. Fonte unica usada tanto pela pre-visualizacao quanto
     * pelo envio real - assim o que o operador confere e exatamente o que sai.
     */
    private EmailPreparado prepararEmailPronto(Processo p, String chave, String assunto, String corpo) {
        switch (chave) {
            case "medicos", "convite-avaliador", "convite-portal" -> {
                var emails = p.getPareceres().stream()
                    .map(par -> par.getMembro().getEmail())
                    .filter(e -> e != null && !e.isBlank())
                    .toArray(String[]::new);
                if (emails.length == 0) {
                    return EmailPreparado.erro("Nenhum avaliador deste processo tem e-mail cadastrado.");
                }
                return EmailPreparado.ok(emails, String.join(", ", emails), assunto, corpo);
            }
            case "solicita-info", "deferido", "indeferido" -> {
                String email = p.getSolicitanteEmail();
                if (email == null || email.isBlank()) {
                    return EmailPreparado.erro("Processo sem e-mail do solicitante cadastrado.");
                }
                // Templates "deferido"/"indeferido" so sao oferecidos quando
                // p.getStatus() ja e o correspondente (EmailTemplateService),
                // entao a mesma checagem de ProcessoValidator.validarRespostaSolicitante
                // (usada em ProcessoService.confirmarRespostaSolicitante) vale aqui -
                // fonte unica da regra, sem reimplementar SNT/oficio inline.
                if (("deferido".equals(chave) || "indeferido".equals(chave))) {
                    var bloqueio = validator.validarRespostaSolicitante(p);
                    if (bloqueio.isPresent()) {
                        return EmailPreparado.erro(bloqueio.get().replace(
                            "antes de confirmar a resposta ao solicitante", "antes de enviar este e-mail"));
                    }
                }
                // Os templates "deferido"/"indeferido" (EmailTemplateService) dizem
                // explicitamente ao solicitante "segue em anexo" o comprovante
                // SNT/oficio - o e-mail precisa de fato levar esse anexo, senao o
                // texto promete algo que nao chega. TipoAnexo ja foi confirmado
                // presente pela checagem de validarRespostaSolicitante acima.
                Anexo anexo = "deferido".equals(chave)
                    ? anexoStorage.buscarUltimoPorTipo(p.getId(), TipoAnexo.COMPROVANTE_SNT)
                    : "indeferido".equals(chave)
                        ? anexoStorage.buscarUltimoPorTipo(p.getId(), TipoAnexo.OFICIO_INDEFERIMENTO)
                        : null;
                return EmailPreparado.ok(new String[]{email}, email, assunto, corpo, anexo);
            }
            default -> {
                return EmailPreparado.erro("Tipo de e-mail desconhecido: " + chave);
            }
        }
    }

    /** Resultado interno de {@link #prepararEmailPronto}: pronto para enviar ou erro. */
    private record EmailPreparado(String[] to, String destinatarios, String assunto, String corpo,
                                  Anexo anexo, String erro) {
        static EmailPreparado ok(String[] to, String destinatarios, String assunto, String corpo) {
            return new EmailPreparado(to, destinatarios, assunto, corpo, null, null);
        }
        static EmailPreparado ok(String[] to, String destinatarios, String assunto, String corpo, Anexo anexo) {
            return new EmailPreparado(to, destinatarios, assunto, corpo, anexo, null);
        }
        static EmailPreparado erro(String erro) {
            return new EmailPreparado(null, null, null, null, null, erro);
        }
        boolean ok() {
            return erro == null;
        }
    }
}
