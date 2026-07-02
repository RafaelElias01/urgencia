package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.TipoAnexo;
import br.gov.saude.sgpur.repository.AnexoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Cobre a allowlist de extensao de upload (PDF/e-mail/imagem) - bloqueia
 * armazenar executaveis/scripts disfarcados de anexo clinico ou comprobatorio.
 */
@ExtendWith(MockitoExtension.class)
class AnexoStorageServiceTest {

    @Mock
    private AnexoRepository anexoRepository;

    private AnexoStorageService service;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        service = new AnexoStorageService(anexoRepository, tempDir.toString());
    }

    private Processo processo() {
        Processo p = new Processo();
        p.setNumero("07/2026");
        p.setPacienteNome("Mariana da Rosa Martins");
        return p;
    }

    @Test
    void salvarAceitaPdf() throws Exception {
        when(anexoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        MockMultipartFile arquivo = new MockMultipartFile(
            "arquivo", "solicitacao.pdf", "application/pdf", "conteudo".getBytes());
        var anexo = service.salvar(processo(), TipoAnexo.SOLICITACAO_RECEBIDA, "desc", arquivo);
        assertThat(anexo.getNomeArquivo()).isEqualTo("solicitacao.pdf");
    }

    @Test
    void salvarRejeitaExecutavel() {
        MockMultipartFile arquivo = new MockMultipartFile(
            "arquivo", "malware.exe", "application/octet-stream", "conteudo".getBytes());
        assertThatThrownBy(() -> service.salvar(processo(), TipoAnexo.SOLICITACAO_RECEBIDA, "desc", arquivo))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("nao permitido");
    }

    @Test
    void salvarRejeitaScriptDisfarcadoDeDocumento() {
        MockMultipartFile arquivo = new MockMultipartFile(
            "arquivo", "documento-clinico.html", "text/html", "<script>alert(1)</script>".getBytes());
        assertThatThrownBy(() -> service.salvar(processo(), TipoAnexo.DOCUMENTO_CLINICO_AVALIADOR, "desc", arquivo))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void salvarRespostaAvaliadorRejeitaExtensaoNaoPermitida() {
        MockMultipartFile arquivo = new MockMultipartFile(
            "arquivo", "resposta.zip", "application/zip", "conteudo".getBytes());
        assertThatThrownBy(() -> service.salvarRespostaAvaliador(processo(), null, "desc", arquivo))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
