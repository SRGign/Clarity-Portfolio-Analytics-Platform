package com.pnltracker.hyperliquid;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;

@Component
public class HyperliquidInfoClient {

    private final RestClient restClient;

    public HyperliquidInfoClient() {
        this.restClient = RestClient.builder()
                .baseUrl("https://api.hyperliquid.xyz")
                .build();
    }

    public CallResult query(String type, Map<String, Object> extraBody) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        body.putAll(extraBody);

        JsonNode response = restClient.post()
                .uri("/info")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        return new CallResult(type, body.toString(), response);
    }

    public record CallResult(String type, String requestBody, JsonNode responseBody) {
    }
}
