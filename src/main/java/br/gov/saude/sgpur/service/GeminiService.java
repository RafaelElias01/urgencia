package br.gov.saude.sgpur.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Optional;

/**
 * Servico de integracao com o Google Gemini API (IA generativa).
 * <p>
 * Usado para assistencia, sugestao de textos, analise de documentos
 * ou qualquer tarefa que se beneficie de IA.
 * <p>
 * A chave da API deve ser configurada via env var {@code SGPUR_GEMINI_KEY}
 * ou no arquivo local {@code application-local.yml}.
 */
@Service
public class GeminiService {

    private static final Logger log = LoggerFactory.getLogger(GeminiService.class);
    private static final String API_URL = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent";

    private final RestClient restClient;
    private final ObjectMapper mapper;
    private final String model;
    private final String apiKey;
    private final boolean enabled;

    public GeminiService(@Value("${app.gemini.api-key:}") String apiKey,
                         @Value("${app.gemini.model:gemini-2.0-flash}") String model,
                         @Value("${app.gemini.enabled:true}") boolean enabled) {
        this.apiKey = apiKey;
        this.model = model;
        // Kill-switch de protecao de dados: em producao o padrao e DESLIGADO
        // (application-prod.yml), pois os recursos de IA enviam conteudo (inclusive
        // documentos clinicos e e-mails com nome do paciente) a um provedor externo
        // (Google). So deve ser habilitado (SGPUR_GEMINI_ENABLED=true) apos decisao
        // formal de tratamento de dados / anonimizacao.
        this.enabled = enabled;
        if (!enabled) {
            log.info("GeminiService: recursos de IA DESLIGADOS (app.gemini.enabled=false). "
                + "Nenhum dado sera enviado ao provedor externo.");
        }
        // Timeouts explicitos: sem eles uma chamada pendurada a Gemini prende a
        // thread da requisicao indefinidamente. Conexao curta, leitura maior
        // (geracao de texto pode demorar alguns segundos).
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(30));
        this.restClient = RestClient.builder().requestFactory(factory).build();
        this.mapper = new ObjectMapper();
    }

    /**
     * Envia uma mensagem de texto para o Gemini e retorna a resposta.
     *
     * @param prompt texto de entrada (pergunta / instrucao)
     * @return Optional com a resposta textual, ou vazio se erro / chave nao configurada
     */
    public Optional<String> perguntar(String prompt) {
        if (!enabled) {
            log.debug("GeminiService: chamada ignorada - IA desligada (app.gemini.enabled=false).");
            return Optional.empty();
        }
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("GeminiService: chave da API nao configurada. Defina SGPUR_GEMINI_KEY.");
            return Optional.empty();
        }
        try {
            String url = API_URL.formatted(model) + "?key=" + apiKey;

            ObjectNode body = mapper.createObjectNode();
            ArrayNode contents = body.putArray("contents");
            ObjectNode content = contents.addObject();
            ArrayNode parts = content.putArray("parts");
            parts.addObject().put("text", prompt);

            String json = mapper.writeValueAsString(body);

            String resposta = restClient.post()
                .uri(url)
                .header("Content-Type", "application/json")
                .body(json)
                .retrieve()
                .body(String.class);

            return extrairTexto(resposta);
        } catch (Exception e) {
            log.error("GeminiService: erro ao chamar a API: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Extrai o texto da resposta JSON da Gemini.
     */
    private Optional<String> extrairTexto(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode textNode = root.path("candidates")
                .path(0).path("content")
                .path("parts").path(0).path("text");
            if (textNode.isMissingNode() || textNode.isNull()) {
                String erro = root.path("promptFeedback").path("blockReason").asText();
                log.warn("GeminiService: resposta bloqueada ou vazia: {}", erro);
                return Optional.empty();
            }
            return Optional.of(textNode.asText());
        } catch (Exception e) {
            log.error("GeminiService: erro ao extrair texto da resposta: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Verifica se a chave da API foi configurada e o servico esta disponivel.
     */
    public boolean isDisponivel() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }
}
