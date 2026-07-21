package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.StatusProcesso;
import br.gov.saude.sgpur.domain.TipoAnexo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Testes de DecisaoFinalService.gerarDocumentos - a logica pos-decisao que
 * gera Oficio de Indeferimento (so no INDEFERIDO) e Relatorio Final (em
 * qualquer status finalizado: DEFERIDO/INDEFERIDO/CANCELADO). Cobre tambem a
 * origem real do IllegalStateException que ProcessoDecisaoControllerTest so
 * mockava: uma falha de IO ao persistir o PDF gerado
 * (AnexoStorageService.salvarBytes declara "throws IOException").
 */
@ExtendWith(MockitoExtension.class)
class DecisaoFinalServiceTest {

    @Mock
    private ProcessoService processoService;
    @Mock
    private OficioService oficioService;
    @Mock
    private RelatorioService relatorioService;
    @Mock
    private AnexoStorageService anexoStorage;

    private DecisaoFinalService service;

    @BeforeEach
    void setUp() {
        service = new DecisaoFinalService(processoService, oficioService, relatorioService, anexoStorage);
    }

    private Processo processo(StatusProcesso status) {
        Processo p = new Processo();
        p.setId(1L);
        p.setNumero("01/2026");
        p.setStatus(status);
        return p;
    }

    // ---------- INDEFERIDO ----------

    @Test
    void indeferidoGeraOficioERelatorioFinal() throws IOException {
        Processo p = processo(StatusProcesso.INDEFERIDO);
        p.setDataEmissaoOficio(LocalDate.of(2026, 1, 10));
        byte[] oficioBytes = "oficio".getBytes();
        byte[] relatorioBytes = "relatorio".getBytes();
        when(oficioService.gerar(p)).thenReturn(oficioBytes);
        when(relatorioService.gerar(p)).thenReturn(relatorioBytes);

        service.gerarDocumentos(p);

        verify(anexoStorage).removerPorTipo(1L, TipoAnexo.OFICIO_INDEFERIMENTO);
        verify(anexoStorage).salvarBytes(eq(p), eq(TipoAnexo.OFICIO_INDEFERIMENTO),
            anyString(), eq("oficio-indeferimento-01-2026.pdf"), eq("application/pdf"), eq(oficioBytes));

        verify(anexoStorage).removerPorTipo(1L, TipoAnexo.RELATORIO_FINAL);
        verify(anexoStorage).salvarBytes(eq(p), eq(TipoAnexo.RELATORIO_FINAL),
            anyString(), eq("relatorio-processo-01-2026.pdf"), eq("application/pdf"), eq(relatorioBytes));

        // dataEmissaoOficio ja estava preenchida: nao deve setar de novo nem salvar o processo
        verify(processoService, never()).salvar(any());
    }

    @Test
    void indeferidoSemDataEmissaoOficioPreencheComHojeESalva() throws IOException {
        Processo p = processo(StatusProcesso.INDEFERIDO);
        p.setDataEmissaoOficio(null);
        when(oficioService.gerar(p)).thenReturn("oficio".getBytes());
        when(relatorioService.gerar(p)).thenReturn("relatorio".getBytes());

        service.gerarDocumentos(p);

        assertThat(p.getDataEmissaoOficio()).isEqualTo(LocalDate.now());
        verify(processoService).salvar(p);
    }

    @Test
    void indeferidoRemoveOficioAntigoAntesDeGerarNovo() throws IOException {
        Processo p = processo(StatusProcesso.INDEFERIDO);
        p.setDataEmissaoOficio(LocalDate.now());
        when(oficioService.gerar(p)).thenReturn(new byte[0]);
        when(relatorioService.gerar(p)).thenReturn(new byte[0]);

        service.gerarDocumentos(p);

        verify(anexoStorage).removerPorTipo(p.getId(), TipoAnexo.OFICIO_INDEFERIMENTO);
    }

    @Test
    void numeroComBarraViraTracoNoNomeDoArquivo() throws IOException {
        Processo p = processo(StatusProcesso.INDEFERIDO);
        p.setNumero("123/2026");
        p.setDataEmissaoOficio(LocalDate.now());
        when(oficioService.gerar(p)).thenReturn(new byte[0]);
        when(relatorioService.gerar(p)).thenReturn(new byte[0]);

        service.gerarDocumentos(p);

        ArgumentCaptor<String> nomes = ArgumentCaptor.forClass(String.class);
        verify(anexoStorage, times(2)).salvarBytes(eq(p), any(), anyString(), nomes.capture(), anyString(), any());
        assertThat(nomes.getAllValues())
            .containsExactly("oficio-indeferimento-123-2026.pdf", "relatorio-processo-123-2026.pdf");
    }

    // ---------- DEFERIDO / CANCELADO: so relatorio final ----------

    @Test
    void deferidoNaoGeraOficioMasGeraRelatorioFinal() throws IOException {
        Processo p = processo(StatusProcesso.DEFERIDO);
        when(relatorioService.gerar(p)).thenReturn("relatorio".getBytes());

        service.gerarDocumentos(p);

        verify(oficioService, never()).gerar(any());
        verify(anexoStorage, never()).removerPorTipo(1L, TipoAnexo.OFICIO_INDEFERIMENTO);
        verify(anexoStorage, never()).salvarBytes(any(), eq(TipoAnexo.OFICIO_INDEFERIMENTO),
            anyString(), anyString(), anyString(), any());

        verify(anexoStorage).removerPorTipo(1L, TipoAnexo.RELATORIO_FINAL);
        verify(anexoStorage).salvarBytes(eq(p), eq(TipoAnexo.RELATORIO_FINAL),
            anyString(), eq("relatorio-processo-01-2026.pdf"), eq("application/pdf"), any());
        verify(processoService, never()).salvar(any());
    }

    @Test
    void canceladoNaoGeraOficioMasGeraRelatorioFinal() throws IOException {
        Processo p = processo(StatusProcesso.CANCELADO);
        when(relatorioService.gerar(p)).thenReturn("relatorio".getBytes());

        service.gerarDocumentos(p);

        verify(oficioService, never()).gerar(any());
        verify(anexoStorage).removerPorTipo(1L, TipoAnexo.RELATORIO_FINAL);
        verify(anexoStorage).salvarBytes(eq(p), eq(TipoAnexo.RELATORIO_FINAL),
            anyString(), anyString(), anyString(), any());
    }

    // ---------- status ainda em andamento: nao gera nada ----------

    @Test
    void statusEmAndamentoNaoGeraDocumentoAlgum() {
        for (StatusProcesso status : new StatusProcesso[] {
                StatusProcesso.SOLICITADO, StatusProcesso.ENVIADO,
                StatusProcesso.EM_ANALISE, StatusProcesso.SOLICITA_INFORMACAO}) {
            Processo p = processo(status);

            service.gerarDocumentos(p);

            verifyNoInteractions(oficioService, relatorioService, anexoStorage, processoService);
        }
    }

    // ---------- IllegalStateException real: falha de IO ao salvar o PDF ----------

    /**
     * ProcessoDecisaoControllerTest so MOCKA decisaoFinalService lancando
     * IllegalStateException; aqui testamos a condicao real que dispara isso
     * em producao: AnexoStorageService.salvarBytes falha com IOException
     * (ex.: disco cheio/sem permissao) ao persistir o Oficio gerado.
     */
    @Test
    void falhaDeIoAoSalvarOficioLancaIllegalStateExceptionEInterrompeAntesDoRelatorio() throws IOException {
        Processo p = processo(StatusProcesso.INDEFERIDO);
        p.setDataEmissaoOficio(LocalDate.now());
        when(oficioService.gerar(p)).thenReturn(new byte[0]);
        when(anexoStorage.salvarBytes(eq(p), eq(TipoAnexo.OFICIO_INDEFERIMENTO),
                anyString(), anyString(), anyString(), any()))
            .thenThrow(new IOException("disco cheio"));

        assertThatThrownBy(() -> service.gerarDocumentos(p))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("falhou ao gerar o oficio")
            .hasMessageContaining("disco cheio")
            .hasCauseInstanceOf(IOException.class);

        // a excecao do oficio interrompe o metodo - o relatorio final (que
        // viria depois, pois INDEFERIDO tambem e isFinalizado()) nunca chega
        // a ser gerado.
        verify(relatorioService, never()).gerar(any());
        verify(anexoStorage, never()).removerPorTipo(1L, TipoAnexo.RELATORIO_FINAL);
    }

    @Test
    void falhaDeIoAoSalvarRelatorioFinalLancaIllegalStateExceptionParaDeferido() throws IOException {
        Processo p = processo(StatusProcesso.DEFERIDO);
        when(relatorioService.gerar(p)).thenReturn(new byte[0]);
        when(anexoStorage.salvarBytes(eq(p), eq(TipoAnexo.RELATORIO_FINAL),
                anyString(), anyString(), anyString(), any()))
            .thenThrow(new IOException("permissao negada"));

        assertThatThrownBy(() -> service.gerarDocumentos(p))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("falhou ao gerar o relatorio final")
            .hasMessageContaining("permissao negada")
            .hasCauseInstanceOf(IOException.class);
    }

    @Test
    void falhaDeIoAoSalvarRelatorioFinalLancaIllegalStateExceptionParaIndeferidoAposOficioOk() throws IOException {
        // No caminho INDEFERIDO, o oficio e salvo com sucesso mas o relatorio
        // final (gerado na sequencia, pois INDEFERIDO tambem e finalizado)
        // falha - deve propagar o IllegalStateException do relatorio, nao do
        // oficio, e o oficio ja deve ter sido persistido antes da falha.
        Processo p = processo(StatusProcesso.INDEFERIDO);
        p.setDataEmissaoOficio(LocalDate.now());
        when(oficioService.gerar(p)).thenReturn(new byte[0]);
        when(relatorioService.gerar(p)).thenReturn(new byte[0]);
        when(anexoStorage.salvarBytes(eq(p), eq(TipoAnexo.OFICIO_INDEFERIMENTO),
                anyString(), anyString(), anyString(), any()))
            .thenReturn(null);
        when(anexoStorage.salvarBytes(eq(p), eq(TipoAnexo.RELATORIO_FINAL),
                anyString(), anyString(), anyString(), any()))
            .thenThrow(new IOException("falha de disco"));

        assertThatThrownBy(() -> service.gerarDocumentos(p))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("falhou ao gerar o relatorio final");

        verify(anexoStorage).salvarBytes(eq(p), eq(TipoAnexo.OFICIO_INDEFERIMENTO),
            anyString(), anyString(), anyString(), any());
    }
}
