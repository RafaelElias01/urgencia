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
import java.util.UUID;

/**
 * Armazena os arquivos anexados em disco e registra os metadados no banco.
 */
@Service
public class AnexoStorageService {

    private final AnexoRepository anexoRepository;
    private final Path raiz;

    public AnexoStorageService(AnexoRepository anexoRepository,
                               @Value("${app.anexos.dir:./data/anexos}") String dir) {
        this.anexoRepository = anexoRepository;
        this.raiz = Paths.get(dir).toAbsolutePath().normalize();
    }

    /**
     * Retorna o diretorio de armazenamento do processo usando o nome legivel
     * derivado de {@code processo.identificacao()}. Para retrocompatibilidade
     * com registros antigos que usavam "processo-{id}", o metodo
     * {@link #resolverArquivo(Anexo)} ja faz o fallback automatico.
     */
    public Path resolverDirProcesso(Processo processo) {
        String nome = processo.identificacao()
                .replace("/", "-")
                .replaceAll("[\\\\:*?\"<>|]", "_");
        if (nome.length() > 120) {
            nome = nome.substring(0, 120);
        }
        return raiz.resolve(nome);
    }

    @Transactional
    public Anexo salvar(Processo processo, TipoAnexo tipo, String descricao, MultipartFile arquivo)
            throws IOException {
        if (arquivo == null || arquivo.isEmpty()) {
            throw new IllegalArgumentException("Arquivo vazio.");
        }
        Path pastaProcesso = resolverDirProcesso(processo);
        Files.createDirectories(pastaProcesso);

        String original = arquivo.getOriginalFilename() == null ? "anexo" : arquivo.getOriginalFilename();
        String nomeFisico = UUID.randomUUID() + "_" + original.replaceAll("[^A-Za-z0-9._-]", "_");
        Path destino = pastaProcesso.resolve(nomeFisico);

        try (InputStream in = arquivo.getInputStream()) {
            Files.copy(in, destino);
        }

        Anexo anexo = new Anexo();
        anexo.setProcesso(processo);
        anexo.setTipo(tipo);
        anexo.setDescricao(descricao);
        anexo.setNomeArquivo(original);
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
        Path pastaProcesso = resolverDirProcesso(processo);
        Files.createDirectories(pastaProcesso);

        String original = arquivo.getOriginalFilename() == null ? "anexo" : arquivo.getOriginalFilename();
        String nomeFisico = UUID.randomUUID() + "_" + original.replaceAll("[^A-Za-z0-9._-]", "_");
        Path destino = pastaProcesso.resolve(nomeFisico);

        try (InputStream in = arquivo.getInputStream()) {
            Files.copy(in, destino);
        }

        Anexo anexo = new Anexo();
        anexo.setProcesso(processo);
        anexo.setParecer(parecer);
        anexo.setTipo(TipoAnexo.RESPOSTA_AVALIADOR);
        anexo.setDescricao(descricao);
        anexo.setNomeArquivo(original);
        anexo.setContentType(arquivo.getContentType());
        anexo.setTamanhoBytes(arquivo.getSize());
        anexo.setCaminhoArmazenado(raiz.relativize(destino).toString());
        return anexoRepository.save(anexo);
    }

    /** Salva um arquivo a partir de bytes (ex.: Relatorio Final gerado em PDF). */
    @Transactional
    public Anexo salvarBytes(Processo processo, TipoAnexo tipo, String descricao,
                             String nomeArquivo, String contentType, byte[] dados) throws IOException {
        Path pastaProcesso = resolverDirProcesso(processo);
        Files.createDirectories(pastaProcesso);
        String nomeFisico = UUID.randomUUID() + "_" + nomeArquivo.replaceAll("[^A-Za-z0-9._-]", "_");
        Path destino = pastaProcesso.resolve(nomeFisico);
        Files.write(destino, dados);

        Anexo anexo = new Anexo();
        anexo.setProcesso(processo);
        anexo.setTipo(tipo);
        anexo.setDescricao(descricao);
        anexo.setNomeArquivo(nomeArquivo);
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
        return raiz.resolve(anexo.getCaminhoArmazenado()).normalize();
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
