package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.AnexoSolicitacaoOnline;
import br.gov.saude.sgpur.domain.SolicitacaoOnline;
import br.gov.saude.sgpur.repository.AnexoSolicitacaoOnlineRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Testes de {@link AnexoSolicitacaoOnlineStorageService}. Como
 * {@code validarTipoPermitido}/{@code nomeArquivoUnico} sao privados, sao
 * cobertos indiretamente atraves de {@code salvar()}.
 */
@ExtendWith(MockitoExtension.class)
class AnexoSolicitacaoOnlineStorageServiceTest {

    @TempDir
    Path tempDir;

    @Mock
    private AnexoSolicitacaoOnlineRepository repository;

    private AnexoSolicitacaoOnlineStorageService service;
    private SolicitacaoOnline solicitacao;

    @BeforeEach
    void setUp() {
        service = new AnexoSolicitacaoOnlineStorageService(repository, tempDir.toString());
        solicitacao = new SolicitacaoOnline();
        solicitacao.setId(1L);
    }

    private void stubSaveRetornandoOArgumento() {
        when(repository.save(org.mockito.ArgumentMatchers.any(AnexoSolicitacaoOnline.class)))
            .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void aceitaExtensaoPdf() throws IOException {
        stubSaveRetornandoOArgumento();
        MockMultipartFile arquivo = new MockMultipartFile("documentos", "laudo.pdf",
            "application/pdf", "conteudo".getBytes());

        AnexoSolicitacaoOnline salvo = service.salvar(solicitacao, arquivo);

        assertThat(salvo.getNomeArquivo()).isEqualTo("laudo.pdf");
    }

    @Test
    void aceitaExtensaoEml() throws IOException {
        stubSaveRetornandoOArgumento();
        MockMultipartFile arquivo = new MockMultipartFile("documentos", "copia.eml",
            "message/rfc822", "conteudo".getBytes());

        AnexoSolicitacaoOnline salvo = service.salvar(solicitacao, arquivo);

        assertThat(salvo.getNomeArquivo()).isEqualTo("copia.eml");
    }

    @Test
    void aceitaExtensaoMsg() throws IOException {
        stubSaveRetornandoOArgumento();
        MockMultipartFile arquivo = new MockMultipartFile("documentos", "copia.msg",
            "application/octet-stream", "conteudo".getBytes());

        AnexoSolicitacaoOnline salvo = service.salvar(solicitacao, arquivo);

        assertThat(salvo.getNomeArquivo()).isEqualTo("copia.msg");
    }

    @Test
    void aceitaExtensaoPngJpgJpeg() throws IOException {
        stubSaveRetornandoOArgumento();
        assertThat(service.salvar(solicitacao, new MockMultipartFile("documentos", "a.png",
            "image/png", "x".getBytes())).getNomeArquivo()).isEqualTo("a.png");
        assertThat(service.salvar(solicitacao, new MockMultipartFile("documentos", "b.jpg",
            "image/jpeg", "x".getBytes())).getNomeArquivo()).isEqualTo("b.jpg");
        assertThat(service.salvar(solicitacao, new MockMultipartFile("documentos", "c.jpeg",
            "image/jpeg", "x".getBytes())).getNomeArquivo()).isEqualTo("c.jpeg");
    }

    @Test
    void aceitaExtensaoEmMaiusculaPorSerCaseInsensitive() throws IOException {
        stubSaveRetornandoOArgumento();
        MockMultipartFile arquivo = new MockMultipartFile("documentos", "LAUDO.PDF",
            "application/pdf", "conteudo".getBytes());

        AnexoSolicitacaoOnline salvo = service.salvar(solicitacao, arquivo);

        assertThat(salvo.getNomeArquivo()).isEqualTo("LAUDO.PDF");
    }

    @Test
    void rejeitaExtensaoNaoPermitida() {
        MockMultipartFile arquivo = new MockMultipartFile("documentos", "malware.exe",
            "application/octet-stream", "conteudo".getBytes());

        assertThatThrownBy(() -> service.salvar(solicitacao, arquivo))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("nao permitido");
    }

    @Test
    void rejeitaArquivoSemExtensao() {
        MockMultipartFile arquivo = new MockMultipartFile("documentos", "semextensao",
            "application/octet-stream", "conteudo".getBytes());

        assertThatThrownBy(() -> service.salvar(solicitacao, arquivo))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("nao permitido");
    }

    @Test
    void rejeitaArquivoVazio() {
        MockMultipartFile arquivo = new MockMultipartFile("documentos", "vazio.pdf",
            "application/pdf", new byte[0]);

        assertThatThrownBy(() -> service.salvar(solicitacao, arquivo))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("vazio");
    }

    @Test
    void dedupeDeNomeQuandoJaExisteArquivoComMesmoNome() throws IOException {
        stubSaveRetornandoOArgumento();
        MockMultipartFile arquivo1 = new MockMultipartFile("documentos", "laudo.pdf",
            "application/pdf", "primeiro".getBytes());
        MockMultipartFile arquivo2 = new MockMultipartFile("documentos", "laudo.pdf",
            "application/pdf", "segundo".getBytes());

        AnexoSolicitacaoOnline salvo1 = service.salvar(solicitacao, arquivo1);
        AnexoSolicitacaoOnline salvo2 = service.salvar(solicitacao, arquivo2);

        assertThat(salvo1.getNomeArquivo()).isEqualTo("laudo.pdf");
        assertThat(salvo2.getNomeArquivo()).isEqualTo("laudo (2).pdf");
    }

    @Test
    void resolverArquivoRejeitaCaminhoForaDaAreaDeArmazenamentoEstiloUnix() {
        AnexoSolicitacaoOnline anexo = new AnexoSolicitacaoOnline();
        anexo.setCaminhoArmazenado("../../../etc/passwd");

        assertThatThrownBy(() -> service.resolverArquivo(anexo))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("invalido");
    }

    @Test
    void resolverArquivoRejeitaCaminhoForaDaAreaDeArmazenamentoEstiloWindows() {
        AnexoSolicitacaoOnline anexo = new AnexoSolicitacaoOnline();
        anexo.setCaminhoArmazenado("..\\..\\..\\Windows\\win.ini");

        assertThatThrownBy(() -> service.resolverArquivo(anexo))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("invalido");
    }

    @Test
    void resolverArquivoAceitaCaminhoValidoDentroDaArea() throws IOException {
        stubSaveRetornandoOArgumento();
        MockMultipartFile arquivo = new MockMultipartFile("documentos", "laudo.pdf",
            "application/pdf", "conteudo".getBytes());
        AnexoSolicitacaoOnline salvo = service.salvar(solicitacao, arquivo);

        Path resolvido = service.resolverArquivo(salvo);

        assertThat(resolvido).exists();
    }
}
