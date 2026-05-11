package com.pnltracker.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.List;

@Component
public class GeminiApiClient {

    private final RestClient restClient;
    private final GeminiProperties properties;
    private final ObjectMapper objectMapper;

    public GeminiApiClient(GeminiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(10));
        requestFactory.setReadTimeout(Duration.ofSeconds(60));
        this.restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    public String completeJson(String systemPrompt, String userMessage) {
        return complete(systemPrompt, List.of(new GeminiMessage("user", userMessage)), true);
    }

    public String completeText(String systemPrompt, List<GeminiMessage> messages) {
        return complete(systemPrompt, messages, false);
    }

    private String complete(String systemPrompt, List<GeminiMessage> messages, boolean jsonResponse) {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new AiAdvisorException("AI_DISABLED", "Gemini API key is not configured");
        }

        GeminiRequest request = GeminiRequest.from(systemPrompt, messages, properties.getMaxTokens(), jsonResponse);
        try {
            String responseBody = restClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v1beta/models/{model}:generateContent")
                            .queryParam("key", properties.getApiKey())
                            .build(properties.getModel()))
                    .body(request)
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (req, resp) -> {
                        int statusCode = resp.getStatusCode().value();
                        if (statusCode == 429) {
                            throw new AiAdvisorException(
                                    "AI_RATE_LIMITED",
                                    "Gemini quota limit reached. Retry after the quota resets.");
                        }
                        throw new AiAdvisorException(
                                "AI_UPSTREAM_ERROR",
                                "Gemini request failed with HTTP " + statusCode);
                    })
                    .body(String.class);
            return extractText(responseBody);
        } catch (AiAdvisorException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw new AiAdvisorException("AI_UPSTREAM_ERROR", "Gemini request failed", exception);
        }
    }

    String extractText(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode candidate = root.path("candidates").path(0);
            String finishReason = candidate.path("finishReason").asText("");
            if ("MAX_TOKENS".equalsIgnoreCase(finishReason)) {
                throw new AiAdvisorException(
                        "AI_RESPONSE_TRUNCATED",
                        "AI response was truncated before completion. Retry the question or ask for fewer options.");
            }

            JsonNode parts = candidate.path("content").path("parts");
            StringBuilder textBuilder = new StringBuilder();
            if (parts.isArray()) {
                for (JsonNode part : parts) {
                    String partText = part.path("text").asText(null);
                    if (partText != null && !partText.isBlank()) {
                        textBuilder.append(partText);
                    }
                }
            }
            String text = textBuilder.toString();
            if (text == null || text.isBlank()) {
                throw new AiAdvisorException("AI_EMPTY_RESPONSE", "Gemini returned an empty response");
            }
            return text;
        } catch (AiAdvisorException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AiAdvisorException("AI_RESPONSE_ERROR", "Unable to parse Gemini response", exception);
        }
    }

    private record GeminiRequest(
            @JsonProperty("system_instruction")
            SystemInstruction systemInstruction,
            List<Content> contents,
            GenerationConfig generationConfig) {

        static GeminiRequest from(
                String systemPrompt,
                List<GeminiMessage> messages,
                Integer maxTokens,
                boolean jsonResponse) {
            GenerationConfig generationConfig = jsonResponse
                    ? new GenerationConfig(maxTokens, 0.3d, "application/json")
                    : new GenerationConfig(maxTokens, 0.3d, null);
            return new GeminiRequest(
                    new SystemInstruction(List.of(new Part(systemPrompt))),
                    messages.stream()
                            .map(message -> new Content(message.role(), List.of(new Part(message.text()))))
                            .toList(),
                    generationConfig);
        }
    }

    private record SystemInstruction(List<Part> parts) {
    }

    private record Content(String role, List<Part> parts) {
    }

    private record Part(String text) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record GenerationConfig(Integer maxOutputTokens, double temperature, String responseMimeType) {
    }
}
