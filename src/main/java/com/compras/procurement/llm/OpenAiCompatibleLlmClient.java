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
public class OpenAiCompatibleLlmClient implements LlmClient, ToolCallingLlmClient, VisionLlmClient {

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

    /**
     * The image goes to the vision model as an OpenAI-style content array on the last user message. Thinking is
     * not requested: reading a delivery note is extraction, and the vision model is the fast one.
     */
    @Override
    public LlmResponse completeJsonWithImage(List<ChatMessage> messages, String imageDataUrl) {
        if (!properties.hasApiKey()) {
            throw new LlmUnavailableException(
                    "The model API key is not configured. Put llm.api-key=... in secrets.properties and restart.");
        }
        if (properties.visionModel() == null || properties.visionModel().isBlank()) {
            throw new LlmUnavailableException("No vision model is configured (llm.vision-model).");
        }
        java.util.List<Object> wire = new java.util.ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage m = messages.get(i);
            if (i == messages.size() - 1 && "user".equals(m.role())) {
                wire.add(Map.of("role", "user", "content", List.of(
                        Map.of("type", "text", "text", m.content()),
                        Map.of("type", "image_url", "image_url", Map.of("url", imageDataUrl)))));
            } else {
                wire.add(Map.of("role", m.role(), "content", m.content()));
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.visionModel());
        body.put("messages", wire);
        body.put("response_format", Map.of("type", "json_object"));
        body.put("temperature", 0.0);
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
            throw new LlmUnavailableException("Image reading failed (" + properties.visionModel() + "): "
                    + e.getMessage(), e);
        }
    }

    @Override
    public ChatResult chat(List<AgentMessage> messages, List<ToolSpec> tools) {
        if (!properties.hasApiKey()) {
            throw new LlmUnavailableException(
                    "The model API key is not configured. Put llm.api-key=... in secrets.properties and restart.");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.model());
        body.put("messages", messages.stream().map(this::toWire).toList());
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools.stream().map(t -> Map.of("type", "function", "function", Map.of(
                    "name", t.name(), "description", t.description(), "parameters", t.parameters()))).toList());
            body.put("tool_choice", "auto");
        }
        body.put("stream", false);
        if (properties.thinkingEnabled()) {
            body.put("thinking", Map.of("type", "enabled"));
            if (properties.reasoningEffort() != null) {
                body.put("reasoning_effort", properties.reasoningEffort());
            }
        } else {
            body.put("thinking", Map.of("type", "disabled"));
            body.put("temperature", properties.temperature());
        }
        try {
            JsonNode response = restClient.post()
                    .uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null) {
                throw new LlmUnavailableException("The model returned no response.");
            }
            JsonNode choice = response.path("choices").path(0);
            JsonNode message = choice.path("message");
            java.util.List<ToolCall> calls = new java.util.ArrayList<>();
            for (JsonNode tc : message.path("tool_calls")) {
                calls.add(new ToolCall(tc.path("id").asText(), tc.path("function").path("name").asText(),
                        tc.path("function").path("arguments").asText("{}")));
            }
            Map<String, Object> usage = response.has("usage")
                    ? objectMapper.convertValue(response.get("usage"), new TypeReference<Map<String, Object>>() {
            }) : Map.of();
            String content = message.path("content").isNull() ? null : message.path("content").asText(null);
            String reasoning = message.path("reasoning_content").isNull() ? null
                    : message.path("reasoning_content").asText(null);
            if ((content == null || content.isBlank()) && calls.isEmpty()) {
                throw new LlmUnavailableException("The model returned an empty reply.");
            }
            return new ChatResult(content, calls, reasoning, choice.path("finish_reason").asText(null), usage);
        } catch (RestClientException e) {
            throw new LlmUnavailableException("Model call failed: " + e.getMessage(), e);
        }
    }

    /** OpenAI wire format; null fields are left out because some providers reject them. */
    private Map<String, Object> toWire(AgentMessage m) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("role", m.role());
        wire.put("content", m.content() == null ? "" : m.content());
        if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
            wire.put("tool_calls", m.toolCalls().stream().map(c -> Map.of("id", c.id(), "type", "function",
                    "function", Map.of("name", c.name(), "arguments", c.arguments()))).toList());
        }
        if (m.toolCallId() != null) {
            wire.put("tool_call_id", m.toolCallId());
        }
        if (properties.thinkingEnabled() && m.reasoningContent() != null) {
            wire.put("reasoning_content", m.reasoningContent());
        }
        return wire;
    }
}
