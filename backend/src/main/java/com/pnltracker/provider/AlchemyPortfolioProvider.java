package com.pnltracker.provider;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.pnltracker.config.PortfolioProperties;

@Component
public class AlchemyPortfolioProvider implements AssetPortfolioProvider {

    private static final int MAX_ADDRESSES_PER_REQUEST = 2;
    private static final int MAX_NETWORKS_PER_REQUEST = 5;
    private static final Logger log = LoggerFactory.getLogger(AlchemyPortfolioProvider.class);

    private final RestClient restClient;
    private final PortfolioProperties properties;
    private final Executor alchemyExecutor;

    public AlchemyPortfolioProvider(
            PortfolioProperties properties,
            @Qualifier("alchemyExecutor") Executor alchemyExecutor) {
        this.restClient = RestClient.builder()
                .baseUrl(properties.getAlchemy().getBaseUrl())
                .build();
        this.properties = properties;
        this.alchemyExecutor = alchemyExecutor;
    }

    @Override
    public ProviderFetchResult fetchAssetsWithDebug(List<String> addresses, List<String> networks) {
        List<CompletableFuture<FetchTaskResult>> futures = new ArrayList<>();
        for (List<String> addressChunk : chunk(addresses, MAX_ADDRESSES_PER_REQUEST)) {
            for (List<String> networkChunk : chunk(networks, MAX_NETWORKS_PER_REQUEST)) {
                List<String> addressChunkCopy = List.copyOf(addressChunk);
                List<String> networkChunkCopy = List.copyOf(networkChunk);
                futures.add(CompletableFuture.supplyAsync(
                        () -> fetchChunkWithFallback(addressChunkCopy, networkChunkCopy),
                        alchemyExecutor));
            }
        }

        List<ProviderAsset> assets = new ArrayList<>();
        List<ProviderExchange> exchanges = new ArrayList<>();
        List<String> skippedNetworks = new ArrayList<>();
        for (CompletableFuture<FetchTaskResult> future : futures) {
            FetchTaskResult result = future.join();
            assets.addAll(result.assets());
            exchanges.addAll(result.exchanges());
            for (String skippedNetwork : result.skippedNetworks()) {
                if (skippedNetworks.stream().noneMatch(network -> network.equalsIgnoreCase(skippedNetwork))) {
                    skippedNetworks.add(skippedNetwork);
                }
            }
        }
        return new ProviderFetchResult(List.copyOf(assets), List.copyOf(exchanges), List.copyOf(skippedNetworks));
    }

    @Override
    public String providerName() {
        return "alchemy";
    }

    private void fetchChunk(
            List<String> addresses,
            List<String> networks,
            List<ProviderAsset> assets,
            List<ProviderExchange> exchanges) {
        String pageKey = null;
        do {
            CallResult callResult = callAlchemy(addresses, networks, pageKey);
            JsonNode body = callResult.body();
            exchanges.add(new ProviderExchange(
                    addresses,
                    List.copyOf(networks),
                    pageKey,
                    callResult.requestBody(),
                    body.toPrettyString()));
            JsonNode dataNode = body.path("data");
            for (JsonNode tokenNode : dataNode.path("tokens")) {
                ProviderAsset parsed = toProviderAsset(tokenNode);
                if (parsed != null) {
                    assets.add(parsed);
                }
            }
            pageKey = dataNode.path("pageKey").asText(null);
        } while (pageKey != null && !pageKey.isBlank());
    }

    private FetchTaskResult fetchChunkWithFallback(List<String> addresses, List<String> networks) {
        List<ProviderAsset> assets = new ArrayList<>();
        List<ProviderExchange> exchanges = new ArrayList<>();
        List<String> skippedNetworks = new ArrayList<>();
        List<String> activeNetworks = new ArrayList<>(networks);

        while (!activeNetworks.isEmpty()) {
            try {
                fetchChunk(addresses, activeNetworks, assets, exchanges);
                break;
            } catch (HttpClientErrorException.Forbidden exception) {
                if (!isOriginWhitelistFailure(exception)) {
                    throw exception;
                }
                log.warn(
                        "Alchemy token data API rejected request for addresses {} and networks {} due to origin whitelist restrictions; continuing with empty spot assets for this chunk",
                        addresses,
                        activeNetworks);
                break;
            } catch (HttpClientErrorException.BadRequest exception) {
                String unsupportedNetwork = extractUnsupportedNetwork(exception);
                if (unsupportedNetwork == null) {
                    throw exception;
                }
                boolean removed = activeNetworks.removeIf(network -> network.equalsIgnoreCase(unsupportedNetwork));
                if (!removed) {
                    throw exception;
                }
                if (skippedNetworks.stream().noneMatch(network -> network.equalsIgnoreCase(unsupportedNetwork))) {
                    skippedNetworks.add(unsupportedNetwork);
                }
            }
        }

        return new FetchTaskResult(List.copyOf(assets), List.copyOf(exchanges), List.copyOf(skippedNetworks));
    }

    private CallResult callAlchemy(List<String> addresses, List<String> networks, String pageKey) {
        List<Object> addressNodes = addresses.stream()
                .map(address -> Map.of("address", address, "networks", networks))
                .map(value -> (Object) value)
                .toList();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("addresses", addressNodes);
        payload.put("withMetadata", true);
        payload.put("withPrices", true);
        payload.put("includeNativeTokens", true);
        payload.put("includeErc20Tokens", true);
        if (pageKey != null && !pageKey.isBlank()) {
            payload.put("pageKey", pageKey);
        }

        JsonNode body = restClient.post()
                .uri("/data/v1/{apiKey}/assets/tokens/by-address", properties.getAlchemy().getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(JsonNode.class);
        return new CallResult(body, payload.toString());
    }

    private ProviderAsset toProviderAsset(JsonNode tokenNode) {
        String priceText = extractUsdPrice(tokenNode.path("tokenPrices"));
        if (priceText == null) {
            return null;
        }

        JsonNode metadata = tokenNode.path("tokenMetadata");
        int decimals = metadata.path("decimals").asInt(18);
        BigDecimal quantity = humanReadableBalance(tokenNode.path("tokenBalance").asText("0"), decimals);
        BigDecimal priceUsd = new BigDecimal(priceText);
        JsonNode tokenAddressNode = tokenNode.path("tokenAddress");
        String tokenAddress = tokenAddressNode.isMissingNode() || tokenAddressNode.isNull() || tokenAddressNode.asText().isBlank()
                ? null
                : tokenAddressNode.asText();

        return new ProviderAsset(
                tokenNode.path("address").asText().toLowerCase(),
                tokenNode.path("network").asText(),
                tokenAddress,
                metadata.path("symbol").asText(tokenAddress == null ? "NATIVE" : "UNKNOWN"),
                metadata.path("name").asText(tokenAddress == null ? "Native Token" : "Unknown Token"),
                decimals,
                quantity,
                priceUsd,
                tokenAddress == null,
                metadata.path("logo").asText(null));
    }

    private String extractUsdPrice(JsonNode pricesNode) {
        for (JsonNode priceNode : pricesNode) {
            if ("usd".equalsIgnoreCase(priceNode.path("currency").asText())) {
                String value = priceNode.path("value").asText(null);
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        return null;
    }

    private BigDecimal humanReadableBalance(String rawBalance, int decimals) {
        if (rawBalance == null || rawBalance.isBlank()) {
            return BigDecimal.ZERO;
        }
        BigDecimal balance = new BigDecimal(parseBalanceInteger(rawBalance));
        return balance.movePointLeft(Math.max(decimals, 0));
    }

    private BigInteger parseBalanceInteger(String rawBalance) {
        String normalized = rawBalance.trim();
        if (normalized.isEmpty() || "0x".equalsIgnoreCase(normalized)) {
            return BigInteger.ZERO;
        }
        if (normalized.startsWith("0x") || normalized.startsWith("0X")) {
            String hex = normalized.substring(2);
            if (hex.isBlank()) {
                return BigInteger.ZERO;
            }
            return new BigInteger(hex, 16);
        }
        return new BigInteger(normalized);
    }

    private <T> List<List<T>> chunk(List<T> values, int size) {
        List<List<T>> chunks = new ArrayList<>();
        for (int index = 0; index < values.size(); index += size) {
            chunks.add(values.subList(index, Math.min(values.size(), index + size)));
        }
        return chunks;
    }

    private String extractUnsupportedNetwork(HttpClientErrorException.BadRequest exception) {
        String body = exception.getResponseBodyAsString();
        String marker = "Unsupported network:";
        int markerIndex = body.indexOf(marker);
        if (markerIndex < 0) {
            return null;
        }
        String tail = body.substring(markerIndex + marker.length()).trim();
        int quoteIndex = tail.indexOf('"');
        if (quoteIndex >= 0) {
            tail = tail.substring(0, quoteIndex);
        }
        int braceIndex = tail.indexOf('}');
        if (braceIndex >= 0) {
            tail = tail.substring(0, braceIndex);
        }
        return tail.trim();
    }

    private boolean isOriginWhitelistFailure(HttpClientErrorException.Forbidden exception) {
        String body = exception.getResponseBodyAsString();
        if (body == null || body.isBlank()) {
            return false;
        }
        return body.toLowerCase().contains("origin not on whitelist");
    }

    private record CallResult(JsonNode body, String requestBody) {
    }

    private record FetchTaskResult(
            List<ProviderAsset> assets,
            List<ProviderExchange> exchanges,
            List<String> skippedNetworks) {
    }
}
