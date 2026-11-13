package com.compras.procurement.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bound from the "llm" section of application.yml (and secrets.properties for the key). */
@ConfigurationProperties("llm")
public record LlmProperties(
        String baseUrl,
        String model,
        String apiKey,
        int timeoutSeconds,
        double temperature,
        /** "enabled" or "disabled": DeepSeek's thinking mode. Enabled is slower but reasons more. */
        String thinking,
        /** low | high | max, only sent when thinking is enabled. */
        String reasoningEffort
) {
    public boolean thinkingEnabled() {
        return "enabled".equalsIgnoreCase(thinking);
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank() && !apiKey.contains("REPLACE_ME");
    }
}
