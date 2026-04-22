package com.pnltracker.zerion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class ZerionApiClient {

    private static final Logger log = LoggerFactory.getLogger(ZerionApiClient.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final Pattern ETH_ADDRESS = Pattern.compile("^0x[0-9a-fA-F]{40}$");

    public record ZerionFetchResult(List<ZerionPosition> positions, boolean stale) {}

    private final RestClient restClient;
    private final ZerionProperties properties;
    private final ObjectMapper objectMapper;

    public ZerionApiClient(ZerionProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(10));
        requestFactory.setReadTimeout(Duration.ofSeconds(35));
        this.restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    private String authorizationHeader() {
        String credentials = properties.getApiKey() + ":";
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    public ZerionFetchResult getDeFiPositions(String address) {
        if (!ETH_ADDRESS.matcher(address).matches()) {
            log.warn("Zerion rejected invalid address format: {}", address);
            return new ZerionFetchResult(List.of(), false);
        }

        String url = "/wallets/" + address + "/positions/"
                + "?filter[positions]=only_complex&currency=usd"
                + "&filter[position_types]=deposit,loan,locked,staked,reward";

        int attempt = 0;
        while (attempt < MAX_ATTEMPTS) {
            attempt++;
            long startMs = System.currentTimeMillis();
            try {
                String responseBody = restClient.get()
                        .uri(url)
                        .header("Authorization", authorizationHeader())
                        .retrieve()
                        .onStatus(status -> status.value() == 429, (req, resp) -> {
                            throw new RetryableException(429);
                        })
                        .onStatus(status -> status.value() == 503, (req, resp) -> {
                            throw new RetryableException(503);
                        })
                        .body(String.class);

                long durationMs = System.currentTimeMillis() - startMs;
                log.info("Zerion GET {} status=200 durationMs={}", url, durationMs);

                return new ZerionFetchResult(parsePositions(responseBody), false);

            } catch (RetryableException e) {
                long durationMs = System.currentTimeMillis() - startMs;
                log.info("Zerion GET {} status={} durationMs={}", url, e.status, durationMs);
                if (attempt >= MAX_ATTEMPTS) {
                    log.warn("Zerion retry exhausted after {} attempts for address={}", MAX_ATTEMPTS, address);
                    return new ZerionFetchResult(List.of(), true);
                }
                try {
                    Thread.sleep(e.status == 429 ? 1000L : 3000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return new ZerionFetchResult(List.of(), true);
                }
            } catch (Exception e) {
                long durationMs = System.currentTimeMillis() - startMs;
                log.warn("Zerion GET {} failed durationMs={} error={}", url, durationMs, e.getMessage());
                return new ZerionFetchResult(List.of(), true);
            }
        }
        return new ZerionFetchResult(List.of(), true);
    }

    private List<ZerionPosition> parsePositions(String json) {
        List<ZerionPosition> result = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode data = root.path("data");
            if (!data.isArray()) return result;
            for (JsonNode item : data) {
                result.add(mapPosition(item));
            }
        } catch (Exception e) {
            log.warn("Zerion JSON parse error: {}", e.getMessage());
        }
        return result;
    }

    private ZerionPosition mapPosition(JsonNode item) {
        JsonNode attrs = item.path("attributes");
        JsonNode changes = attrs.path("changes");
        JsonNode fungible = attrs.path("fungible_info");
        JsonNode implementations = fungible.path("implementations");

        String chainId = null;
        String tokenAddress = null;
        if (implementations.isArray() && implementations.size() > 0) {
            JsonNode impl0 = implementations.get(0);
            chainId = textOrNull(impl0.path("chain_id"));
            tokenAddress = textOrNull(impl0.path("address"));
        }

        return new ZerionPosition(
            textOrNull(attrs.path("position_type")),
            doubleOrNull(attrs.path("value")),
            doubleOrNull(attrs.path("price")),
            doubleOrNull(attrs.path("quantity").path("float")),
            intOrNull(attrs.path("quantity").path("decimals")),
            doubleOrNull(changes.path("absolute_1d")),
            doubleOrNull(changes.path("percent_1d")),
            textOrNull(attrs.path("protocol")),
            textOrNull(attrs.path("protocol_module")),
            textOrNull(attrs.path("pool_address")),
            textOrNull(attrs.path("group_id")),
            textOrNull(attrs.path("protocol_name")),
            textOrNull(attrs.path("protocol_url")),
            textOrNull(fungible.path("name")),
            textOrNull(fungible.path("symbol")),
            textOrNull(fungible.path("icon").path("url")),
            chainId,
            tokenAddress,
            textOrNull(attrs.path("updated_at")),
            textOrNull(attrs.path("chain")),
            textOrNull(attrs.path("application_metadata").path("url"))
        );
    }

    private String textOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        String text = node.asText(null);
        return (text == null || text.isBlank()) ? null : text;
    }

    private Double doubleOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        return node.isNumber() ? node.doubleValue() : null;
    }

    private Integer intOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        return node.isNumber() ? node.intValue() : null;
    }

    static class RetryableException extends RuntimeException {
        final int status;
        RetryableException(int status) {
            super("HTTP " + status);
            this.status = status;
        }
    }
}
