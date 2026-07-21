package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.StatusProcesso;
import br.gov.saude.sgpur.domain.TipoAnexo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDate;

/**
 * Encapsula a geracao automatica dos documentos PDF (Oficio de Indeferimento
 * e Relatorio Final) apos uma decisao final — seja ela manual ou automatica.
 *
 * Centraliza a logica que antes estava duplicada em ProcessoController e
 * permite que AvaliadorController tambem a utilize apos a decisao automatica
 * disparada pelo voto do portal.
 */
@Service
public class DecisaoFinalService {

    private static final Logger log = LoggerFactory.getLogger(DecisaoFinalService.class);

    private final ProcessoService processoService;
    private final OficioService oficioService;
    private final RelatorioService relatorioService;
    private final AnexoStorageService anexoStorage;

    public DecisaoFinalService(ProcessoService processoService,
                               OficioService oficioService,
                               RelatorioService relatorioService,
                               AnexoStorageService anexoStorage) {
        this.processoService = processoService;
        this.oficioService = oficioService;
        this.relatorioService = relatorioService;
        this.anexoStorage = anexoStorage;
    }

    /**
     * Gera e anexa os PDFs correspondentes a decisao ja gravada no processo:
     * - INDEFERIDO: gera o Oficio de Indeferimento (com data de emissao = hoje
     *   se ainda nao preenchida) e o Relatorio Final.
     * - DEFERIDO / CANCELADO: gera apenas o Relatorio Final.
     * Erros de geracao de PDF sao logados e lancados para que o chamador possa
     * exibir avisos sem desfazer a decisao (ja persistida).
     *
     * @param p   Processo ja com status final gravado no banco.
     * @throws IllegalStateException se a geracao de algum PDF falhar.
     */
    public void gerarDocumentos(Processo p) {
        Long id = p.getId();

        if (p.getStatus() == StatusProcesso.INDEFERIDO) {
            if (p.getDataEmissaoOficio() == null) {
                p.setDataEmissaoOficio(LocalDate.now());
                processoService.salvar(p);
            }
            try {
                anexoStorage.removerPorTipo(id, TipoAnexo.OFICIO_INDEFERIMENTO);
                byte[] of = oficioService.gerar(p);
                String nomeOf = "oficio-indeferimento-" + p.getNumero().replace("/", "-") + ".pdf";
                anexoStorage.salvarBytes(p, TipoAnexo.OFICIO_INDEFERIMENTO,
                    "Oficio de indeferimento gerado na decisao", nomeOf, "application/pdf", of);
            } catch (IOException e) {
                log.error("Falha ao gerar oficio de indeferimento para processo {}", p.getNumero(), e);
                throw new IllegalStateException(
                    "Decisao salva, mas falhou ao gerar o oficio: " + e.getMessage(), e);
            }
        }

        if (p.getStatus().isFinalizado()) {
            try {
                anexoStorage.removerPorTipo(id, TipoAnexo.RELATORIO_FINAL);
                byte[] pdf = relatorioService.gerar(p);
                String nome = "relatorio-processo-" + p.getNumero().replace("/", "-") + ".pdf";
                anexoStorage.salvarBytes(p, TipoAnexo.RELATORIO_FINAL,
                    "Relatorio final gerado na decisao", nome, "application/pdf", pdf);
            } catch (IOException e) {
                log.error("Falha ao gerar relatorio final para processo {}", p.getNumero(), e);
                throw new IllegalStateException(
                    "Decisao salva, mas falhou ao gerar o relatorio final: " + e.getMessage(), e);
            }
        }
    }
}
