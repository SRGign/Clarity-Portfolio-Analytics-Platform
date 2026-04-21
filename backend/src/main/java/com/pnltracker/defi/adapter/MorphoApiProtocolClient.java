package com.pnltracker.defi.adapter;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;

@Component
public class MorphoApiProtocolClient implements MorphoProtocolClient {

    private static final String MORPHO_GRAPHQL_URL = "https://blue-api.morpho.org/graphql";
    private static final Map<String, Integer> CHAIN_IDS_BY_NETWORK = Map.of(
            "eth-mainnet", 1,
            "base-mainnet", 8453);

    private final RestClient restClient;

    public MorphoApiProtocolClient() {
        this.restClient = RestClient.builder()
                .baseUrl(MORPHO_GRAPHQL_URL)
                .build();
    }

    @Override
    public List<MorphoMarketPosition> fetchPositions(List<String> addresses, List<String> networks) {
        List<MorphoMarketPosition> positions = new ArrayList<>();
        for (String network : networks) {
            Integer chainId = CHAIN_IDS_BY_NETWORK.get(normalize(network));
            if (chainId == null) {
                continue;
            }
            for (String address : addresses) {
                positions.addAll(fetchWalletPositions(address, network, chainId));
            }
        }
        return List.copyOf(positions);
    }

    private List<MorphoMarketPosition> fetchWalletPositions(String address, String network, int chainId) {
        JsonNode body = restClient.post()
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("query", positionsQuery(address, chainId)))
                .retrieve()
                .body(JsonNode.class);

        JsonNode marketPositions = body.path("data").path("userByAddress").path("marketPositions");
        if (!marketPositions.isArray()) {
            return List.of();
        }

        List<MorphoMarketPosition> positions = new ArrayList<>();
        for (JsonNode node : marketPositions) {
            MorphoMarketPosition position = toPosition(address, network, node);
            if (position != null) {
                positions.add(position);
            }
        }
        return List.copyOf(positions);
    }

    private MorphoMarketPosition toPosition(String address, String network, JsonNode node) {
        JsonNode marketNode = node.path("market");
        JsonNode stateNode = node.path("state");
        if (marketNode.isMissingNode() || marketNode.isNull() || stateNode.isMissingNode() || stateNode.isNull()) {
            return null;
        }

        JsonNode loanAssetNode = marketNode.path("loanAsset");
        JsonNode collateralAssetNode = marketNode.path("collateralAsset");

        BigDecimal supplyQuantity = decimalOrZero(stateNode.path("supplyAssets"));
        BigDecimal supplyUsd = decimalOrZero(stateNode.path("supplyAssetsUsd"));
        BigDecimal borrowQuantity = decimalOrZero(stateNode.path("borrowAssets"));
        BigDecimal borrowUsd = decimalOrZero(stateNode.path("borrowAssetsUsd"));
        BigDecimal collateralQuantity = scaledIntegerOrZero(
                stateNode.path("collateral"),
                collateralAssetNode.path("decimals").asInt(18));
        BigDecimal collateralUsd = decimalOrZero(stateNode.path("collateralUsd"));

        if (supplyUsd.signum() <= 0 && borrowUsd.signum() <= 0 && collateralUsd.signum() <= 0) {
            return null;
        }

        return new MorphoMarketPosition(
                address.toLowerCase(Locale.ROOT),
                normalize(network),
                textOrNull(marketNode.path("uniqueKey")),
                textOrNull(loanAssetNode.path("address")),
                textOrNull(loanAssetNode.path("symbol")),
                textOrNull(loanAssetNode.path("name")),
                integerOrNull(loanAssetNode.path("decimals")),
                textOrNull(collateralAssetNode.path("address")),
                textOrNull(collateralAssetNode.path("symbol")),
                textOrNull(collateralAssetNode.path("name")),
                integerOrNull(collateralAssetNode.path("decimals")),
                supplyQuantity,
                supplyUsd,
                borrowQuantity,
                borrowUsd,
                collateralQuantity,
                collateralUsd);
    }

    private String positionsQuery(String address, int chainId) {
        return "query {"
                + " userByAddress(chainId: " + chainId + ", address: \\\"" + address + "\\\") {"
                + "   marketPositions {"
                + "     market {"
                + "       uniqueKey"
                + "       loanAsset { address symbol name decimals }"
                + "       collateralAsset { address symbol name decimals }"
                + "     }"
                + "     state {"
                + "       supplyAssets"
                + "       supplyAssetsUsd"
                + "       borrowAssets"
                + "       borrowAssetsUsd"
                + "       collateral"
                + "       collateralUsd"
                + "     }"
                + "   }"
                + " }"
                + "}";
    }

    private BigDecimal decimalOrZero(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return BigDecimal.ZERO;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        String text = node.asText(null);
        if (text == null || text.isBlank()) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(text);
    }

    private BigDecimal scaledIntegerOrZero(JsonNode node, int decimals) {
        String text = node == null || node.isMissingNode() || node.isNull() ? null : node.asText(null);
        if (text == null || text.isBlank()) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(new BigInteger(text)).movePointLeft(Math.max(decimals, 0));
    }

    private String textOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String text = node.asText(null);
        return text == null || text.isBlank() ? null : text;
    }

    private Integer integerOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.isNumber() ? node.intValue() : Integer.valueOf(node.asText());
    }

    private String normalize(String network) {
        return network == null ? "" : network.trim().toLowerCase(Locale.ROOT);
    }
}
