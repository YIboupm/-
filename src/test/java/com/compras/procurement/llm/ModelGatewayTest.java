package com.compras.procurement.llm;

import com.compras.procurement.audit.AuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ModelGatewayTest {

    record Answer(String value) {
    }

    @Test
    void retriesOneTransientEmptyProviderReply() {
        AtomicInteger calls = new AtomicInteger();
        LlmClient client = messages -> {
            if (calls.getAndIncrement() == 0) {
                throw new LlmUnavailableException("The model returned an empty reply.");
            }
            return new LlmResponse("{\"value\":\"ok\"}", Map.of("completion_tokens", 3));
        };
        AuditService audit = mock(AuditService.class);
        ModelGateway gateway = new ModelGateway(client, new ObjectMapper(), audit);

        Answer answer = gateway.call("test", "conversation-1", List.of(ChatMessage.user("hello")), Answer.class);

        assertEquals("ok", answer.value());
        assertEquals(2, calls.get());
        verify(audit).record(anyString(), anyString(), any());
    }
}
