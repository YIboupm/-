package com.compras.procurement.llm;

import java.util.List;

/**
 * A model that can call tools. Kept separate from {@link LlmClient} so simple JSON calls (and the
 * tests that implement LlmClient as a lambda) are unaffected.
 */
public interface ToolCallingLlmClient {

    ChatResult chat(List<AgentMessage> messages, List<ToolSpec> tools);
}
