package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.Anexo;
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

    @Transactional
    public Anexo salvar(Processo processo, TipoAnexo tipo, String descricao, MultipartFile arquivo)
            throws IOException {
        if (arquivo == null || arquivo.isEmpty()) {
            throw new IllegalArgumentException("Arquivo vazio.");
        }
        Path pastaProcesso = raiz.resolve("processo-" + processo.getId());
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

    public Path resolverArquivo(Anexo anexo) {
        return raiz.resolve(anexo.getCaminhoArmazenado()).normalize();
    }

    public Anexo buscar(Long id) {
        return anexoRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Anexo nao encontrado: " + id));
    }

    /** Remove a pasta de anexos de um processo (usado ao excluir o processo). */
    public void removerPastaProcesso(Long processoId) {
        Path pasta = raiz.resolve("processo-" + processoId).normalize();
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
