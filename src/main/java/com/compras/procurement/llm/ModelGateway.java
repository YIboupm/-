package com.compras.procurement.llm;

import com.compras.procurement.audit.AuditService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The only door to the model. It enforces structured output (parse into a Java type, one repair
 * attempt if the JSON is invalid) and records every call in the audit log with its purpose and
 * latency, so model behaviour can be reviewed and evaluated later.
 */
@Service
public class ModelGateway {

    private static final Logger log = LoggerFactory.getLogger(ModelGateway.class);

    private final LlmClient client;
    private final ObjectMapper objectMapper;
    private final AuditService audit;
    private ToolCallingLlmClient toolClient;
    private VisionLlmClient visionClient;

    public ModelGateway(LlmClient client, ObjectMapper objectMapper, AuditService audit) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.audit = audit;
        this.toolClient = client instanceof ToolCallingLlmClient t ? t : null;
        this.visionClient = client instanceof VisionLlmClient v ? v : null;
    }

    /** Lets tests plug in an image-reading client. */
    public void setVisionClient(VisionLlmClient visionClient) {
        this.visionClient = visionClient;
    }

    /**
     * Structured output from text plus one image. The image is not written to the audit log (only that there was
     * one and its size); the reply is.
     */
    public <T> T callWithImage(String purpose, String correlationId, List<ChatMessage> messages, String imageDataUrl,
                               Class<T> type) {
        if (visionClient == null) {
            throw new LlmUnavailableException("The configured model client cannot read images.");
        }
        long start = System.currentTimeMillis();
        String raw = null;
        Map<String, Object> usage = null;
        String label = purpose + " (image, " + (imageDataUrl == null ? 0 : imageDataUrl.length() / 1024) + " KB)";
        try {
            LlmResponse response = visionClient.completeJsonWithImage(messages, imageDataUrl);
            raw = response.content();
            usage = response.usage();
            T parsed = parse(raw, type);
            record(label, correlationId, start, true, raw, usage, null);
            return parsed;
        } catch (JsonProcessingException e) {
            record(label, correlationId, start, false, raw, usage, e.getOriginalMessage());
            throw new LlmUnavailableException("The model did not return valid JSON.", e);
        } catch (LlmUnavailableException e) {
            record(label, correlationId, start, false, raw, usage, e.getMessage());
            throw e;
        }
    }

    /** Lets tests (or another provider) plug in a tool-calling client. */
    public void setToolClient(ToolCallingLlmClient toolClient) {
        this.toolClient = toolClient;
    }

    /** One agent step with tools. Every call is audited with its latency, token usage and requested tools. */
    public ChatResult chat(String purpose, String correlationId, List<AgentMessage> messages, List<ToolSpec> tools) {
        if (toolClient == null) {
            throw new LlmUnavailableException("The configured model client does not support tool calls.");
        }
        long start = System.currentTimeMillis();
        try {
            ChatResult result;
            try {
                result = toolClient.chat(messages, tools);
            } catch (LlmUnavailableException first) {
                if (first.getMessage() == null || !first.getMessage().contains("empty reply")) {
                    throw first;
                }
                log.warn("Empty model reply for {}, retrying once", purpose);
                result = toolClient.chat(messages, tools);
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("purpose", purpose);
            payload.put("latencyMs", System.currentTimeMillis() - start);
            payload.put("ok", true);
            payload.put("response", result.content());
            payload.put("toolCalls", result.toolCalls() == null ? List.of() : result.toolCalls().stream()
                    .map(c -> Map.of("name", c.name(), "arguments", c.arguments())).toList());
            payload.put("usage", result.usage() == null ? Map.of() : result.usage());
            audit.record("ModelCall", correlationId, payload);
            return result;
        } catch (LlmUnavailableException e) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("purpose", purpose);
            payload.put("latencyMs", System.currentTimeMillis() - start);
            payload.put("ok", false);
            payload.put("error", e.getMessage());
            audit.record("ModelCall", correlationId, payload);
            throw e;
        }
    }

    public <T> T call(String purpose, String correlationId, List<ChatMessage> messages, Class<T> type) {
        long start = System.currentTimeMillis();
        String raw = null;
        Map<String, Object> usage = null;
        try {
            LlmResponse response = completeWithEmptyReplyRetry(purpose, messages);
            raw = response.content();
            usage = response.usage();
            T parsed;
            try {
                parsed = parse(raw, type);
            } catch (JsonProcessingException first) {
                log.warn("Invalid JSON from model for {}, asking it to repair: {}", purpose, first.getOriginalMessage());
                List<ChatMessage> retry = new ArrayList<>(messages);
                retry.add(ChatMessage.assistant(raw));
                retry.add(ChatMessage.user("Your previous reply was not valid JSON (" + first.getOriginalMessage()
                        + "). Reply again with only the JSON object, no other text."));
                response = completeWithEmptyReplyRetry(purpose + " JSON repair", retry);
                raw = response.content();
                usage = response.usage();
                parsed = parse(raw, type);
            }
            record(purpose, correlationId, start, true, raw, usage, null);
            return parsed;
        } catch (JsonProcessingException e) {
            record(purpose, correlationId, start, false, raw, usage, e.getOriginalMessage());
            throw new LlmUnavailableException("The model did not return valid JSON.", e);
        } catch (LlmUnavailableException e) {
            record(purpose, correlationId, start, false, raw, usage, e.getMessage());
            throw e;
        }
    }

    private <T> T parse(String raw, Class<T> type) throws JsonProcessingException {
        return objectMapper.readValue(stripFences(raw), type);
    }

    /** One bounded retry for a provider response that contains a choice but no message content. */
    private LlmResponse completeWithEmptyReplyRetry(String purpose, List<ChatMessage> messages) {
        try {
            return client.completeJson(messages);
        } catch (LlmUnavailableException first) {
            if (first.getMessage() == null || !first.getMessage().contains("empty reply")) {
                throw first;
            }
            log.warn("Empty model reply for {}, retrying once", purpose);
            return client.completeJson(messages);
        }
    }

    /** Some models wrap JSON in ```json fences even when asked not to. */
    static String stripFences(String raw) {
        String s = raw.trim();
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            int lastFence = s.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                s = s.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return s;
    }

    private void record(String purpose, String correlationId, long start, boolean ok, String raw,
                        Map<String, Object> usage, String error) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("purpose", purpose);
        payload.put("latencyMs", System.currentTimeMillis() - start);
        payload.put("ok", ok);
        payload.put("response", raw);
        if (usage != null) {
            payload.put("usage", usage);
        }
        if (error != null) {
            payload.put("error", error);
        }
        audit.record("ModelCall", correlationId, payload);
    }
}
