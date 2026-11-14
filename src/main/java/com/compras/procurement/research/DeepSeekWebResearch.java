package com.compras.procurement.research;

import com.compras.procurement.llm.LlmProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * DeepSeek's Search and Fetch APIs (POST /v1/tools/search, /v1/tools/fetch), same key as the model.
 * Billed per successful request; see https://api-docs.deepseek.com/guides/search
 */
@Component
public class DeepSeekWebResearch implements WebResearch {

    private final LlmProperties properties;
    private final RestClient rest;

    public DeepSeekWebResearch(LlmProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(30));
        this.rest = builder.baseUrl(properties.baseUrl()).requestFactory(factory).build();
    }

    @Override
    public boolean available() {
        return properties.hasApiKey();
    }

    @Override
    public List<WebResult> search(String query, int count) {
        JsonNode response = post("/v1/tools/search", Map.of("query", query, "count", count));
        List<WebResult> results = new ArrayList<>();
        for (JsonNode r : response.path("results")) {
            results.add(new WebResult(r.path("title").asText(""), r.path("url").asText(""),
                    r.path("snippet").asText(""), r.path("summary").asText(""), r.path("site_name").asText(null),
                    r.path("date").asText(null), r.path("authority").asInt(-1)));
        }
        return results;
    }

    @Override
    public WebPage fetch(String url) {
        JsonNode response = post("/v1/tools/fetch", Map.of("url", url));
        return new WebPage(url, response.path("title").asText(""), response.path("content").asText(""));
    }

    private JsonNode post(String path, Map<String, Object> body) {
        if (!available()) {
            throw new WebResearchException("Web research is not configured (no DeepSeek API key).");
        }
        try {
            JsonNode response = rest.post().uri(path)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null) {
                throw new WebResearchException("Empty response from " + path);
            }
            return response;
        } catch (RestClientResponseException e) {
            throw new WebResearchException(switch (e.getStatusCode().value()) {
                case 402 -> "DeepSeek balance is insufficient for web research.";
                case 403, 404 -> "The page could not be retrieved (blocked or no readable content).";
                case 429 -> "Web research rate limit reached; try again shortly.";
                default -> "Web research failed (HTTP " + e.getStatusCode().value() + ").";
            });
        } catch (RestClientException e) {
            throw new WebResearchException("Web research failed: " + e.getMessage());
        }
    }
}
