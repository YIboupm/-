package com.compras.procurement.llm;

import java.util.List;

/**
 * Provider-neutral model call. Business code depends on {@link ModelGateway}, which depends on
 * this interface, so switching from DeepSeek to another provider (or a self-hosted model) means
 * one new implementation and a config change.
 */
public interface LlmClient {

    /** Sends the conversation and returns the model's reply (expected to be JSON) with token usage. */
    LlmResponse completeJson(List<ChatMessage> messages);
}
