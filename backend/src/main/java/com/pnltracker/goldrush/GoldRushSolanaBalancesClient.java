package com.pnltracker.goldrush;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pnltracker.config.PortfolioProperties;

@Component
public class GoldRushSolanaBalancesClient {

    private static final Logger log = LoggerFactory.getLogger(GoldRushSolanaBalancesClient.class);

    private final PortfolioProperties.GoldRushProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public GoldRushSolanaBalancesClient(PortfolioProperties properties, ObjectMapper objectMapper) {
        this.properties = properties.getGoldrush();
        this.objectMapper = objectMapper;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofSeconds(Math.max(this.properties.getTimeoutSeconds(), 1));
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        this.restClient = RestClient.builder()
                .baseUrl(this.properties.getBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    public List<GoldRushSolanaBalanceItem> fetchBalances(String walletAddress) {
        if (!properties.isEnabled() || !properties.isSolanaBalancesEnabled()) {
            log.debug("GoldRush Solana balances fetch skipped because provider is disabled");
            return List.of();
        }
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            log.warn("GoldRush Solana balances fetch skipped because API key is missing");
            return List.of();
        }
        if (walletAddress == null || walletAddress.isBlank()) {
            return List.of();
        }

        try {
            JsonNode body = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v1/{chainName}/address/{walletAddress}/balances_v2/")
                            .queryParam("no-spam", true)
                            .build(properties.getSolanaChainName(), walletAddress.trim()))
                    .header("Authorization", authorizationHeader())
                    .retrieve()
                    .body(JsonNode.class);

            List<GoldRushSolanaBalanceItem> items = new ArrayList<>();
            for (JsonNode itemNode : body.path("data").path("items")) {
                GoldRushSolanaBalanceItem item = mapItem(itemNode);
                if (item != null) {
                    items.add(item);
                }
            }
            return List.copyOf(items);
        } catch (RuntimeException exception) {
            log.warn("GoldRush Solana balances fetch failed for wallet={}: {}", walletAddress, exception.getMessage());
            return List.of();
        }
    }

    private GoldRushSolanaBalanceItem mapItem(JsonNode itemNode) {
        String mintAddress = textOrNull(itemNode.path("contract_address"));
        String symbol = textOrNull(itemNode.path("contract_ticker_symbol"));
        String name = textOrNull(itemNode.path("contract_name"));
        int decimals = itemNode.path("contract_decimals").asInt(0);
        BigDecimal rawBalance = decimalOrNull(itemNode.path("balance"));
        BigDecimal quantity = rawBalance == null
                ? BigDecimal.ZERO
                : rawBalance.movePointLeft(Math.max(decimals, 0)).stripTrailingZeros();
        BigDecimal priceUsd = decimalOrNull(itemNode.path("quote_rate"));
        BigDecimal valueUsd = decimalOrNull(itemNode.path("quote"));
        String logoUrl = textOrNull(itemNode.path("logo_url"));

        return new GoldRushSolanaBalanceItem(
                mintAddress,
                symbol,
                name,
                decimals,
                rawBalance,
                quantity,
                priceUsd,
                valueUsd,
                logoUrl,
                itemNode.toString());
    }

    private String authorizationHeader() {
        String credentials = properties.getApiKey() + ":";
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    private String textOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String text = node.asText(null);
        return text == null || text.isBlank() ? null : text;
    }

    private BigDecimal decimalOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String text = node.asText(null);
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    public record GoldRushSolanaBalanceItem(
            String mintAddress,
            String symbol,
            String name,
            int decimals,
            BigDecimal rawBalance,
            BigDecimal quantity,
            BigDecimal priceUsd,
            BigDecimal valueUsd,
            String logoUrl,
            String rawPayloadJson) {
    }
}
