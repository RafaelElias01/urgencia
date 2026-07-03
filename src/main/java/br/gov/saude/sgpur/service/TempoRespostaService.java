package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.Parecer;
import br.gov.saude.sgpur.repository.ParecerRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Calcula os indicadores de tempo de resposta dos avaliadores: quantos DIAS
 * CORRIDOS cada avaliador leva, em media, entre o envio do processo
 * (Parecer.dataEnvio) e a sua resposta (Parecer.dataResposta), e a media geral
 * (tempo de resposta medio total).
 *
 * <p>A unidade e o dia corrido porque as duas datas sao {@code LocalDate} (sem
 * hora); 0 dia significa que respondeu no mesmo dia do envio. Pareceres reabertos
 * apos "Solicita informacao" perdem a dataResposta mas mantem a dataEnvio, entao
 * o voto definitivo e medido desde o envio original (comportamento aceito).
 *
 * <p>Segue a convencao do projeto: busca as entidades (fetch) e agrega em Java,
 * sem GROUP BY/AVG no banco.
 */
@Service
public class TempoRespostaService {

    private final ParecerRepository parecerRepo;
    private final int prazoDias;

    public TempoRespostaService(ParecerRepository parecerRepo,
                                @Value("${app.avaliador.prazo-dias:7}") int prazoDias) {
        this.parecerRepo = parecerRepo;
        this.prazoDias = prazoDias;
    }

    /** Estatistica de tempo de resposta de um avaliador (membro). */
    public record TempoMembro(long avaliados, Double mediaDias, long foraDoPrazo) {}

    /** Resumo geral + detalhamento por membro (chave = membroId). */
    public record ResumoTempo(long totalAvaliados, Double mediaGeralDias,
                              long foraDoPrazo, int prazoDias,
                              Map<Long, TempoMembro> porMembro) {}

    public int getPrazoDias() {
        return prazoDias;
    }

    /**
     * Calcula sobre TODOS os pareceres respondidos do sistema (usado em
     * /membros e no Painel). Para um subconjunto especifico (ex.: os
     * pareceres de um ano, no Relatorio Anual), use {@link #calcularDe(List)}.
     */
    public ResumoTempo calcular() {
        return calcularDe(parecerRepo.findRespondidosComDatas());
    }

    /**
     * Mesma agregacao de {@link #calcular()}, mas sobre uma lista de pareceres
     * fornecida pelo chamador (ja filtrada por resultado/dataEnvio/dataResposta
     * nao nulos) — permite reusar o mesmo calculo com um recorte, como os
     * pareceres de um ano especifico no Relatorio Anual, sem outra query.
     */
    public ResumoTempo calcularDe(List<Parecer> respondidos) {
        List<Long> temposGerais = new ArrayList<>();
        long foraGeral = 0;
        Map<Long, List<Long>> temposPorMembro = new LinkedHashMap<>();
        Map<Long, Long> foraPorMembro = new LinkedHashMap<>();

        for (Parecer p : respondidos) {
            long dias = ChronoUnit.DAYS.between(p.getDataEnvio(), p.getDataResposta());
            if (dias < 0) {
                continue; // guarda contra dados inconsistentes
            }
            temposGerais.add(dias);
            if (dias > prazoDias) {
                foraGeral++;
            }
            if (p.getMembro() != null) {
                Long mid = p.getMembro().getId();
                temposPorMembro.computeIfAbsent(mid, k -> new ArrayList<>()).add(dias);
                if (dias > prazoDias) {
                    foraPorMembro.merge(mid, 1L, Long::sum);
                }
            }
        }

        Map<Long, TempoMembro> porMembro = new LinkedHashMap<>();
        for (var e : temposPorMembro.entrySet()) {
            porMembro.put(e.getKey(), new TempoMembro(
                e.getValue().size(),
                media(e.getValue()),
                foraPorMembro.getOrDefault(e.getKey(), 0L)));
        }

        return new ResumoTempo(temposGerais.size(), media(temposGerais),
            foraGeral, prazoDias, porMembro);
    }

    private static Double media(List<Long> tempos) {
        if (tempos.isEmpty()) {
            return null;
        }
        return tempos.stream().mapToLong(Long::longValue).average().orElse(0);
    }

    private static final DecimalFormat FMT =
        new DecimalFormat("#,##0.#", new DecimalFormatSymbols(new Locale("pt", "BR")));

    /**
     * Formata a media de dias para exibicao pt-BR: {@code null} vira "—";
     * caso contrario "X,X dia(s)" com uma casa decimal (ex.: "3,5 dias",
     * "1 dia", "0 dias").
     */
    public static String formatarDias(Double mediaDias) {
        if (mediaDias == null) {
            return "—"; // travessao
        }
        String num = FMT.format(mediaDias);
        boolean singular = Math.abs(mediaDias - 1.0) < 0.05;
        return num + (singular ? " dia" : " dias");
    }
}
