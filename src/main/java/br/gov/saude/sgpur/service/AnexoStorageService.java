package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.Anexo;
import br.gov.saude.sgpur.domain.Parecer;
import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.TipoAnexo;
import br.gov.saude.sgpur.repository.AnexoRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Set;

/**
 * Armazena os arquivos anexados em disco e registra os metadados no banco.
 */
@Service
public class AnexoStorageService {

    /**
     * Extensoes aceitas para upload manual (todo o app so pede PDF, e-mail
     * (.eml/.msg) ou imagem do comprovante SNT nos forms - ver accept="" dos
     * templates). Bloqueia executaveis/scripts sendo armazenados como anexo
     * de um sistema de saude.
     */
    private static final Set<String> EXTENSOES_PERMITIDAS =
        Set.of("pdf", "eml", "msg", "png", "jpg", "jpeg");

    private final AnexoRepository anexoRepository;
    private final Path raiz;

    public AnexoStorageService(AnexoRepository anexoRepository,
                               @Value("${app.anexos.dir:./data/anexos}") String dir) {
        this.anexoRepository = anexoRepository;
        this.raiz = Paths.get(dir).toAbsolutePath().normalize();
    }

    /**
     * Retorna o diretorio de armazenamento do processo no padrao legivel
     * {@code "NN-AAAA - Nome do Paciente"} (a barra do numero vira traco, pois
     * "/" e separador de caminho). Para retrocompatibilidade com registros
     * antigos (pasta "processo-{id}" ou a que incluia o RGCT), o metodo
     * {@link #resolverArquivo(Anexo)} usa o caminho gravado no banco, entao
     * downloads de anexos antigos continuam funcionando.
     */
    public Path resolverDirProcesso(Processo processo) {
        String numero = (processo.getNumero() == null || processo.getNumero().isBlank())
                ? "SN" : processo.getNumero().replace("/", "-");
        String nome = numero;
        if (processo.getPacienteNome() != null && !processo.getPacienteNome().isBlank()) {
            nome = numero + " - " + processo.getPacienteNome();
        }
        nome = nome.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (nome.length() > 120) {
            nome = nome.substring(0, 120);
        }
        return raiz.resolve(nome);
    }

    /**
     * Rejeita uploads com extensao fora da allowlist (PDF/e-mail/imagem).
     * Nao ha antivirus nem verificacao de conteudo real do arquivo - so
     * bloqueia o caso obvio de subir um executavel/script disfarcado de
     * anexo clinico/comprobatorio.
     */
    private void validarTipoPermitido(MultipartFile arquivo) {
        String nome = arquivo.getOriginalFilename();
        String extensao = (nome != null && nome.contains("."))
            ? nome.substring(nome.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT)
            : "";
        if (!EXTENSOES_PERMITIDAS.contains(extensao)) {
            throw new IllegalArgumentException(
                "Tipo de arquivo nao permitido (" + extensao + "). Envie PDF, imagem (PNG/JPG) ou e-mail (EML/MSG).");
        }
    }

    /**
     * Nome de arquivo unico dentro da pasta: parte do {@code nomeDesejado} e,
     * se ja existir um arquivo com esse nome, acrescenta " (2)", " (3)"... antes
     * da extensao. Sanitiza caracteres ilegais em nome de arquivo.
     */
    private static String nomeArquivoUnico(Path pasta, String nomeDesejado) {
        String sanitized = nomeDesejado.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        int dot = sanitized.lastIndexOf('.');
        String base = dot > 0 ? sanitized.substring(0, dot) : sanitized;
        String ext = dot > 0 ? sanitized.substring(dot) : "";
        String candidato = sanitized;
        int n = 1;
        while (Files.exists(pasta.resolve(candidato))) {
            n++;
            candidato = base + " (" + n + ")" + ext;
        }
        return candidato;
    }

    @Transactional
    public Anexo salvar(Processo processo, TipoAnexo tipo, String descricao, MultipartFile arquivo)
            throws IOException {
        if (arquivo == null || arquivo.isEmpty()) {
            throw new IllegalArgumentException("Arquivo vazio.");
        }
        validarTipoPermitido(arquivo);
        Path pastaProcesso = resolverDirProcesso(processo);
        Files.createDirectories(pastaProcesso);

        String original = arquivo.getOriginalFilename() == null ? "anexo" : arquivo.getOriginalFilename();
        String nomePadrao = NomePadraoAnexo.gerar(processo, tipo, original, LocalDate.now());
        String nomeFinal = nomeArquivoUnico(pastaProcesso, nomePadrao);
        Path destino = pastaProcesso.resolve(nomeFinal);

        try (InputStream in = arquivo.getInputStream()) {
            Files.copy(in, destino);
        }

        Anexo anexo = new Anexo();
        anexo.setProcesso(processo);
        anexo.setTipo(tipo);
        anexo.setDescricao(descricao);
        anexo.setNomeArquivo(nomeFinal);
        anexo.setContentType(arquivo.getContentType());
        anexo.setTamanhoBytes(arquivo.getSize());
        anexo.setCaminhoArmazenado(raiz.relativize(destino).toString());
        return anexoRepository.save(anexo);
    }

    /**
     * Salva o e-mail de resposta de um avaliador especifico, vinculando o
     * anexo ao {@link Parecer} correspondente.
     */
    @Transactional
    public Anexo salvarRespostaAvaliador(Processo processo, Parecer parecer,
                                         String descricao, MultipartFile arquivo) throws IOException {
        if (arquivo == null || arquivo.isEmpty()) {
            throw new IllegalArgumentException("Arquivo vazio.");
        }
        validarTipoPermitido(arquivo);
        Path pastaProcesso = resolverDirProcesso(processo);
        Files.createDirectories(pastaProcesso);

        String original = arquivo.getOriginalFilename() == null ? "anexo" : arquivo.getOriginalFilename();
        String nomePadrao = NomePadraoAnexo.gerar(processo, TipoAnexo.RESPOSTA_AVALIADOR, original, LocalDate.now());
        String nomeFinal = nomeArquivoUnico(pastaProcesso, nomePadrao);
        Path destino = pastaProcesso.resolve(nomeFinal);

        try (InputStream in = arquivo.getInputStream()) {
            Files.copy(in, destino);
        }

        Anexo anexo = new Anexo();
        anexo.setProcesso(processo);
        anexo.setParecer(parecer);
        anexo.setTipo(TipoAnexo.RESPOSTA_AVALIADOR);
        anexo.setDescricao(descricao);
        anexo.setNomeArquivo(nomeFinal);
        anexo.setContentType(arquivo.getContentType());
        anexo.setTamanhoBytes(arquivo.getSize());
        anexo.setCaminhoArmazenado(raiz.relativize(destino).toString());
        return anexoRepository.save(anexo);
    }

    /**
     * Salva um arquivo a partir de bytes (ex.: Oficio/Relatorio Final gerados
     * na decisao). Aplica o NOME PADRAO, exceto para {@code SOLICITACAO_AVALIADOR},
     * cujo nome oficial ("Processo CET-RS NN-AAAA - Paciente X.X.X.pdf", so
     * iniciais) e definido por {@code SolicitacaoAvaliadorService.nomeArquivoOficial}
     * e nao deve ser sobrescrito (imparcialidade + convencao ja documentada).
     */
    @Transactional
    public Anexo salvarBytes(Processo processo, TipoAnexo tipo, String descricao,
                             String nomeArquivo, String contentType, byte[] dados) throws IOException {
        Path pastaProcesso = resolverDirProcesso(processo);
        Files.createDirectories(pastaProcesso);
        String nomeDesejado = (tipo == TipoAnexo.SOLICITACAO_AVALIADOR)
                ? nomeArquivo
                : NomePadraoAnexo.gerar(processo, tipo, nomeArquivo, LocalDate.now());
        String nomeFinal = nomeArquivoUnico(pastaProcesso, nomeDesejado);
        Path destino = pastaProcesso.resolve(nomeFinal);
        Files.write(destino, dados);

        Anexo anexo = new Anexo();
        anexo.setProcesso(processo);
        anexo.setTipo(tipo);
        anexo.setDescricao(descricao);
        anexo.setNomeArquivo(nomeFinal);
        anexo.setContentType(contentType);
        anexo.setTamanhoBytes((long) dados.length);
        anexo.setCaminhoArmazenado(raiz.relativize(destino).toString());
        return anexoRepository.save(anexo);
    }

    /** Remove todos os anexos de um tipo de um processo (arquivos + registros). */
    @Transactional
    public void removerPorTipo(Long processoId, TipoAnexo tipo) {
        for (Anexo a : anexoRepository.findByProcessoIdAndTipo(processoId, tipo)) {
            try {
                Files.deleteIfExists(resolverArquivo(a));
            } catch (IOException ignored) {
                // best-effort
            }
            anexoRepository.delete(a);
        }
    }

    /**
     * Resolve o arquivo fisico do anexo. Tenta primeiro o caminho gravado no banco
     * (que pode ser relativo a pasta legivel ou a pasta antiga "processo-{id}").
     * Como o {@code caminhoArmazenado} e sempre relativo a raiz, a resolucao ja
     * cobre ambos os casos - este metodo existe para clareza e eventual extensao.
     */
    public Path resolverArquivo(Anexo anexo) {
        Path resolvido = raiz.resolve(anexo.getCaminhoArmazenado()).normalize();
        // Defesa em profundidade: garante que o caminho resolvido continua
        // dentro da raiz de anexos, mesmo que caminhoArmazenado seja corrompido
        // (nunca deveria escapar, pois e gravado pelo proprio sistema).
        if (!resolvido.startsWith(raiz)) {
            throw new IllegalArgumentException("Caminho de anexo invalido (fora da area de armazenamento).");
        }
        return resolvido;
    }

    public Anexo buscar(Long id) {
        return anexoRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Anexo nao encontrado: " + id));
    }

    /** Remove um anexo (arquivo em disco + registro no banco). Retorna o id do processo. */
    @Transactional
    public Long excluir(Long anexoId) {
        Anexo a = buscar(anexoId);
        Long processoId = a.getProcesso().getId();
        try {
            Files.deleteIfExists(resolverArquivo(a));
        } catch (IOException ignored) {
            // best-effort
        }
        anexoRepository.delete(a);
        return processoId;
    }

    /**
     * Remove a pasta de anexos de um processo (usado ao excluir o processo).
     * Tenta remover tanto a pasta pelo ID legado ("processo-{id}") quanto a
     * pasta pelo nome legivel, se existirem.
     */
    public void removerPastaProcesso(Processo processo) {
        removerPasta(raiz.resolve("processo-" + processo.getId()).normalize());
        removerPasta(resolverDirProcesso(processo).normalize());
    }

    /** @deprecated Use {@link #removerPastaProcesso(Processo)}. */
    @Deprecated
    public void removerPastaProcesso(Long processoId) {
        removerPasta(raiz.resolve("processo-" + processoId).normalize());
    }

    private void removerPasta(Path pasta) {
        try {
            if (Files.exists(pasta)) {
                try (var paths = Files.walk(pasta)) {
                    paths.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) { } });
                }
            }
        } catch (IOException ignored) {
            // best-effort: metadados ja removidos do banco
        }
    }
}
