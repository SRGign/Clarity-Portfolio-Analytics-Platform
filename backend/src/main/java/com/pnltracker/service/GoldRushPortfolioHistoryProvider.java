package com.pnltracker.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.databind.JsonNode;
import com.pnltracker.config.PortfolioProperties;
import com.pnltracker.domain.ChainDefinition;

@Component
public class GoldRushPortfolioHistoryProvider implements PortfolioHistoryProvider {

    private static final String SOURCE = "goldrush";
    private static final Map<String, String> NETWORK_ALIASES = Map.of(
            "eth-mainnet", "eth-mainnet",
            "bnb-mainnet", "bsc-mainnet",
            "polygon-mainnet", "matic-mainnet",
            "arb-mainnet", "arbitrum-mainnet",
            "opt-mainnet", "optimism-mainnet",
            "avax-mainnet", "avalanche-mainnet",
            "arbnova-mainnet", "arbitrum-nova-mainnet");

    private final PortfolioProperties.GoldRushProperties properties;
    private final RestClient restClient;

    public GoldRushPortfolioHistoryProvider(PortfolioProperties properties) {
        this.properties = properties.getGoldrush();

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofSeconds(Math.max(this.properties.getTimeoutSeconds(), 1));
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        this.restClient = RestClient.builder()
                .baseUrl(this.properties.getBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public PortfolioHistoryFetchResult fetchHistory(PortfolioHistoryFetchRequest request) {
        if (request.chains().isEmpty()) {
            return new PortfolioHistoryFetchResult(SOURCE, Map.of(), Set.of());
        }

        Set<String> missingChains = new LinkedHashSet<>();
        Map<LocalDate, BigDecimal> totalsByDate = new LinkedHashMap<>();

        if (!properties.isEnabled() || properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            request.chains().stream().map(ChainDefinition::id).forEach(missingChains::add);
            return new PortfolioHistoryFetchResult(SOURCE, totalsByDate, missingChains);
        }

        for (ChainDefinition chain : request.chains()) {
            String chainName = toGoldRushChainName(chain.providerNetwork());
            if (chainName == null || chainName.isBlank()) {
                missingChains.add(chain.id());
                continue;
            }

            Map<LocalDate, BigDecimal> chainTotals = initializeZeros(request.requiredDates());
            int successfulAddresses = 0;

            for (String address : request.addresses()) {
                try {
                    Map<LocalDate, BigDecimal> addressTotals = fetchAddressTotals(chainName, address, request);
                    mergeInto(chainTotals, addressTotals);
                    successfulAddresses++;
                } catch (RestClientException | IllegalStateException exception) {
                    missingChains.add(chain.id());
                }
            }

            if (successfulAddresses > 0) {
                mergeInto(totalsByDate, chainTotals);
            }
        }

        return new PortfolioHistoryFetchResult(SOURCE, totalsByDate, missingChains);
    }

    private Map<LocalDate, BigDecimal> fetchAddressTotals(
            String chainName,
            String address,
            PortfolioHistoryFetchRequest request) {
        JsonNode body = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v1/{chainName}/address/{address}/portfolio_v2/")
                        .queryParam("days", requestedDays(request.requiredDates()))
                        .build(chainName, address))
                .header("Authorization", authorizationHeader())
                .retrieve()
                .body(JsonNode.class);

        Map<LocalDate, BigDecimal> totals = initializeZeros(request.requiredDates());
        for (JsonNode itemNode : body.path("data").path("items")) {
            for (JsonNode holdingNode : itemNode.path("holdings")) {
                LocalDate localDate = parseHoldingDate(holdingNode.path("timestamp").asText(null));
                if (localDate == null || !request.requiredDates().contains(localDate)) {
                    continue;
                }
                BigDecimal closeQuote = decimalOrZero(holdingNode.path("close").path("quote"));
                totals.merge(localDate, closeQuote, BigDecimal::add);
            }
        }
        return totals;
    }

    private String authorizationHeader() {
        String credentials = properties.getApiKey() + ":";
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    private int requestedDays(Set<LocalDate> requiredDates) {
        LocalDate min = requiredDates.stream().min(LocalDate::compareTo).orElse(LocalDate.now(ZoneOffset.UTC));
        LocalDate max = requiredDates.stream().max(LocalDate::compareTo).orElse(min);
        return (int) ChronoUnit.DAYS.between(min, max) + 1;
    }

    private LocalDate parseHoldingDate(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            return null;
        }
        return Instant.parse(timestamp).atZone(ZoneOffset.UTC).toLocalDate();
    }

    private BigDecimal decimalOrZero(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return BigDecimal.ZERO;
        }
        String text = node.asText("0");
        if (text == null || text.isBlank()) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(text);
    }

    private void mergeInto(Map<LocalDate, BigDecimal> target, Map<LocalDate, BigDecimal> source) {
        source.forEach((localDate, totalUsd) -> target.merge(localDate, totalUsd, BigDecimal::add));
    }

    private Map<LocalDate, BigDecimal> initializeZeros(Set<LocalDate> requiredDates) {
        Map<LocalDate, BigDecimal> totals = new LinkedHashMap<>();
        requiredDates.stream()
                .sorted()
                .forEach(localDate -> totals.put(localDate, BigDecimal.ZERO));
        return totals;
    }

    private String toGoldRushChainName(String providerNetwork) {
        return NETWORK_ALIASES.getOrDefault(providerNetwork, providerNetwork);
    }
}
