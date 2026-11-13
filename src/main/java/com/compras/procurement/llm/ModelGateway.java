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

    public ModelGateway(LlmClient client, ObjectMapper objectMapper, AuditService audit) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.audit = audit;
    }

    public <T> T call(String purpose, String correlationId, List<ChatMessage> messages, Class<T> type) {
        long start = System.currentTimeMillis();
        String raw = null;
        Map<String, Object> usage = null;
        try {
            LlmResponse response = client.completeJson(messages);
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
                response = client.completeJson(retry);
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
