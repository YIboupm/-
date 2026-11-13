package com.compras.procurement.llm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Calls any OpenAI-compatible chat-completions endpoint. Configured for DeepSeek by default
 * (base URL https://api.deepseek.com, model deepseek-v4-pro).
 */
@Component
public class OpenAiCompatibleLlmClient implements LlmClient {

    private final LlmProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public OpenAiCompatibleLlmClient(LlmProperties properties, RestClient.Builder builder, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(properties.timeoutSeconds()));
        this.restClient = builder
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .build();
    }

    @Override
    public LlmResponse completeJson(List<ChatMessage> messages) {
        if (!properties.hasApiKey()) {
            throw new LlmUnavailableException(
                    "The model API key is not configured. Put llm.api-key=... in secrets.properties and restart.");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.model());
        body.put("messages", messages);
        body.put("response_format", Map.of("type", "json_object"));
        if (properties.thinkingEnabled()) {
            body.put("thinking", Map.of("type", "enabled"));
            if (properties.reasoningEffort() != null) {
                body.put("reasoning_effort", properties.reasoningEffort());
            }
        } else {
            body.put("thinking", Map.of("type", "disabled"));
            body.put("temperature", properties.temperature());
        }
        body.put("stream", false);

        try {
            JsonNode response = restClient.post()
                    .uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            String content = response == null ? null
                    : response.path("choices").path(0).path("message").path("content").asText(null);
            if (content == null || content.isBlank()) {
                throw new LlmUnavailableException("The model returned an empty reply.");
            }
            Map<String, Object> usage = response.has("usage")
                    ? objectMapper.convertValue(response.get("usage"), new TypeReference<Map<String, Object>>() {
            }) : Map.of();
            return new LlmResponse(content, usage);
        } catch (RestClientException e) {
            throw new LlmUnavailableException("Model call failed: " + e.getMessage(), e);
        }
    }
}
