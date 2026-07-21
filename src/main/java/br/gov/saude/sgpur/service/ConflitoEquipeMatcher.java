package br.gov.saude.sgpur.service;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Detecta suspeita de conflito de interesse: o solicitante do processo ser da
 * MESMA equipe/instituicao de um avaliador. NAO bloqueia nada - so alimenta um
 * aviso na tela de Envio (ProcessoDetalheController -> detalhe.html).
 *
 * <p>O membro guarda so a SIGLA (ex.: "HCPA") e o solicitante e texto livre
 * (ex.: "Hospital de Clinicas de Porto Alegre"). Para casar sigla x nome por
 * extenso x cidade, usa um mapa de apelidos por sigla ({@link #ALIASES}). Para
 * siglas fora do mapa, cai no casamento por tokens da propria instituicao.
 *
 * <p>Comparacao ignora maiusculas/minusculas e acentos, e exige PALAVRA/FRASE
 * inteira (nao substring solto) para reduzir falso-positivo.
 */
@Component
public class ConflitoEquipeMatcher {

    /**
     * Apelidos por sigla (ja normalizados): nome por extenso, cidade e variantes.
     * Enriquecer aqui ao cadastrar novas instituicoes. Siglas ausentes usam os
     * proprios tokens da instituicao. "sem hospital" mapeia para vazio (nunca casa).
     */
    private static final Map<String, Set<String>> ALIASES = Map.of(
        "hcpa", Set.of("hcpa", "hospital de clinicas", "clinicas", "porto alegre", "ufrgs"),
        "iscmpa", Set.of("iscmpa", "santa casa", "misericordia", "porto alegre"),
        "hslpuc", Set.of("hslpuc", "sao lucas", "puc", "pucrs", "porto alegre"),
        "hnsp", Set.of("hnsp", "nossa senhora", "pompeia", "caxias"),
        "hbbl", Set.of("hbbl"),
        "hci", Set.of("hci"),
        "cet rs", Set.of("cet", "central de transplantes", "central estadual"),
        "sem hospital", Set.of() // solicitante nunca "pertence" a instituicao vazia
    );

    /** Palavras genericas ignoradas ao casar por tokens (nao identificam equipe). */
    private static final Set<String> STOPWORDS = Set.of(
        "hospital", "de", "da", "do", "dos", "das", "e", "o", "a", "os", "as",
        "em", "no", "na", "sem");

    /**
     * true se ha suspeita de o solicitante ser da mesma equipe do avaliador.
     */
    public boolean mesmaEquipe(String instituicaoMembro, String equipeSolicitante) {
        String solic = normalizar(equipeSolicitante);
        String inst = normalizar(instituicaoMembro);
        if (solic.isBlank() || inst.isBlank()) {
            return false;
        }
        Set<String> keywords = ALIASES.getOrDefault(inst, tokensSignificativos(inst));
        // Apelido/nome/cidade do membro aparece por inteiro no texto do solicitante.
        for (String kw : keywords) {
            if (contemPalavra(solic, kw)) {
                return true;
            }
        }
        // Bidirecional: uma palavra significativa do solicitante aparece na
        // instituicao do membro (ajuda quando o solicitante escreve a sigla).
        for (String tok : tokensSignificativos(solic)) {
            if (contemPalavra(inst, tok)) {
                return true;
            }
        }
        return false;
    }

    /** Minusculas, sem acento, so [a-z0-9] e espaco simples. */
    static String normalizar(String s) {
        if (s == null) {
            return "";
        }
        String semAcento = Normalizer.normalize(s, Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "");
        return semAcento.toLowerCase(java.util.Locale.ROOT)
            .replaceAll("[^a-z0-9]+", " ")
            .trim();
    }

    /** Tokens com >=3 chars que nao sejam stopwords. */
    private static Set<String> tokensSignificativos(String normalizado) {
        return Arrays.stream(normalizado.split(" "))
            .filter(t -> t.length() >= 3 && !STOPWORDS.contains(t))
            .collect(Collectors.toSet());
    }

    /** Casa {@code termo} (palavra ou frase) como sequencia inteira em {@code texto}. */
    private static boolean contemPalavra(String texto, String termo) {
        if (termo == null || termo.isBlank()) {
            return false;
        }
        return Pattern.compile("\\b" + Pattern.quote(termo) + "\\b")
            .matcher(texto).find();
    }
}
