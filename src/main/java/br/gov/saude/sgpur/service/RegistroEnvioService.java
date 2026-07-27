package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.Anexo;
import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.TipoAnexo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Etapa 2 do fluxo (Envio): monta e carimba o PDF consolidado enviado aos
 * avaliadores e efetiva o registro do envio. Extraido de
 * {@code ProcessoDecisaoController.registrarEnvio}, que fazia toda essa
 * logica de negocio (leitura de bytes, validacao de PDF, consolidacao,
 * carimbo, persistencia) diretamente no controller HTTP.
 *
 * <p>Preserva o mesmo comportamento observavel de antes: mesma ordem "salva o
 * novo anexo antes de remover o antigo" (evita o processo ficar sem nenhum
 * PDF de solicitacao se o meio do caminho falhar), mesmo tratamento de PDF
 * corrompido/sem senha/sem paginas (fica de fora da consolidacao com aviso,
 * nao bloqueia sozinho salvo se nenhum PDF sobrar valido) e as mesmas
 * mensagens de erro/aviso.
 */
@Service
public class RegistroEnvioService {

    private static final Logger log = LoggerFactory.getLogger(RegistroEnvioService.class);

    private final ProcessoService processoService;
    private final SolicitacaoAvaliadorService solicitacaoAvaliadorService;
    private final AnexoStorageService anexoStorage;
    private final AuditoriaService auditoria;

    public RegistroEnvioService(ProcessoService processoService,
                                SolicitacaoAvaliadorService solicitacaoAvaliadorService,
                                AnexoStorageService anexoStorage,
                                AuditoriaService auditoria) {
        this.processoService = processoService;
        this.solicitacaoAvaliadorService = solicitacaoAvaliadorService;
        this.anexoStorage = anexoStorage;
        this.auditoria = auditoria;
    }

    /**
     * Resultado do registro de envio: ou {@code ok=true} com a mensagem de
     * sucesso e a lista de avisos (documentos clinicos que ficaram de fora do
     * PDF consolidado), ou {@code ok=false} com a mensagem de erro (nenhum
     * efeito colateral ocorreu).
     */
    public record RegistroEnvioResultado(boolean ok, String mensagemErro,
                                         String mensagemSucesso, List<String> avisos) {
        public static RegistroEnvioResultado erro(String msg) {
            return new RegistroEnvioResultado(false, msg, null, List.of());
        }

        public static RegistroEnvioResultado sucesso(String msg, List<String> avisos) {
            return new RegistroEnvioResultado(true, null, msg, avisos);
        }
    }

    @Transactional
    public RegistroEnvioResultado registrar(Long processoId) {
        Processo p = processoService.buscar(processoId);
        LocalDate hoje = LocalDate.now();

        // O PDF dos avaliadores e montado SO com os documentos clinicos
        // anonimizados (PDF) anexados pelo operador: funde-os em um unico PDF e
        // carimba, em cada pagina, um cabecalho com nº do processo + INICIAIS do
        // paciente (NUNCA o nome completo - imparcialidade do julgamento). Sem a
        // folha-rosto gerada pelo sistema. A solicitacao ORIGINAL recebida (nome
        // completo) NUNCA entra aqui. Sem nenhum documento clinico PDF nao ha o
        // que enviar: bloqueia o envio.
        List<byte[]> partes = new ArrayList<>();
        List<String> partesNomes = new ArrayList<>();
        List<String> ignorados = new ArrayList<>();
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
                partesNomes.add(doc.getNomeArquivo());
            }
        } catch (IOException e) {
            return RegistroEnvioResultado.erro("Falha ao ler os documentos clinicos: " + e.getMessage());
        }

        if (partes.isEmpty()) {
            String detalhe = ignorados.isEmpty()
                ? "Anexe ao menos um documento clinico (PDF) antes de registrar o envio."
                : "Os documentos anexados nao sao PDF e nao podem ser consolidados ("
                    + String.join(", ", ignorados) + "). Anexe ao menos um documento clinico em PDF.";
            return RegistroEnvioResultado.erro(detalhe);
        }

        // Valida se os PDFs tem paginas antes de consolidar. Um PDF corrompido
        // ou protegido por senha e descartado da consolidacao, mas o nome vai
        // para "ignorados" (mesmo aviso dos anexos nao-PDF) - o operador precisa
        // saber que um documento clinico ficou de fora, em vez de o envio
        // seguir silenciosamente incompleto.
        List<byte[]> validos = new ArrayList<>();
        for (int i = 0; i < partes.size(); i++) {
            byte[] bytes = partes.get(i);
            String nome = partesNomes.get(i);
            try {
                com.lowagie.text.pdf.PdfReader chk = new com.lowagie.text.pdf.PdfReader(bytes);
                if (chk.getNumberOfPages() > 0) {
                    validos.add(bytes);
                } else {
                    ignorados.add(nome + " (PDF sem paginas)");
                }
                chk.close();
            } catch (Exception e) {
                ignorados.add(nome + " (PDF corrompido ou protegido por senha)");
            }
        }
        if (validos.isEmpty()) {
            return RegistroEnvioResultado.erro(
                "Nenhum dos documentos clinicos anexados e um PDF valido com paginas. "
                + "Remova-os e anexe novamente os documentos originais.");
        }
        partes = validos;

        // PRIMEIRO: gera o PDF consolidado com cabecalho carimbado, em memoria,
        // e SALVA o novo anexo. SO DEPOIS de o novo anexo estar gravado com
        // sucesso (arquivo em disco + registro no banco) e que o(s) anexo(s)
        // antigo(s) sao removidos - assim, se consolidar/carimbar/salvar
        // falhar em qualquer ponto, o processo NAO fica sem nenhum PDF de
        // solicitacao aos avaliadores (evita perder um anexo bom por causa de
        // uma tentativa de reenvio que falhou no meio do caminho).
        try {
            byte[] consolidado = solicitacaoAvaliadorService.consolidar(partes);
            byte[] pdfSolicitacao = solicitacaoAvaliadorService.carimbarCabecalho(consolidado, p);
            String nomeSolicitacao = SolicitacaoAvaliadorService.nomeArquivoOficial(p);

            Anexo novoAnexo = anexoStorage.salvarBytes(p, TipoAnexo.SOLICITACAO_AVALIADOR,
                "Copia da solicitacao para envio as equipes (documentos clinicos anonimizados com cabecalho; nome completo suprimido)",
                nomeSolicitacao, "application/pdf", pdfSolicitacao);
            anexoStorage.removerAntigosDoTipo(processoId, TipoAnexo.SOLICITACAO_AVALIADOR, novoAnexo.getId());

            // SO depois de o novo anexo estar seguro, efetiva o envio.
            p.getPareceres().forEach(par -> par.setDataEnvio(hoje));
            processoService.salvar(p);
            processoService.registrarEnvio(processoId);

            auditoria.registrar("ANEXO_ADICIONADO",
                "Processo " + p.getNumero() + " - Solicitacao PDF consolidada (cabecalho carimbado) gerada automaticamente");
        } catch (Exception e) {
            log.error("Erro ao registrar envio do processo {}", processoId, e);
            return RegistroEnvioResultado.erro(
                "Nao foi possivel gerar o PDF de envio. Verifique os documentos clinicos "
                + "anexados (devem ser PDFs validos, nao corrompidos e sem senha) e tente novamente.");
        }

        auditoria.registrar("ENVIO_AVALIADORES_REGISTRADO", "Processo " + p.getNumero());
        String msg = "Envio aos avaliadores registrado em "
            + hoje.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) + ".";
        return RegistroEnvioResultado.sucesso(msg, ignorados);
    }
}
