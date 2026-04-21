package com.pnltracker.hyperliquid;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;

@Service
public class HyperliquidService {

    private static final List<String> PORTFOLIO_PERIODS = List.of("day", "week", "month", "allTime");

    private final HyperliquidInfoClient client;

    public HyperliquidService(HyperliquidInfoClient client) {
        this.client = client;
    }

    public HyperliquidReport fetchReport(String user) {
        List<HyperliquidExchange> exchanges = new ArrayList<>();

        JsonNode roleNode = query("userRole", Map.of("user", user), exchanges);
        JsonNode portfolioNode = query("portfolio", Map.of("user", user), exchanges);
        JsonNode clearinghouseStateNode = query("clearinghouseState", Map.of("user", user), exchanges);
        JsonNode spotStateNode = query("spotClearinghouseState", Map.of("user", user), exchanges);
        JsonNode vaultEquitiesNode = query("userVaultEquities", Map.of("user", user), exchanges);
        JsonNode spotMetaNode = query("spotMetaAndAssetCtxs", Map.of(), exchanges);

        Map<Integer, BigDecimal> spotPrices = buildSpotPrices(spotMetaNode);
        HyperliquidSummary summary = new HyperliquidSummary(
                user,
                roleNode.path("role").asText("missing"),
                latestPortfolioValue(portfolioNode, "day"),
                decimal(clearinghouseStateNode.path("marginSummary").path("accountValue")),
                sumSpotValue(spotStateNode, spotPrices),
                sumVaultValue(vaultEquitiesNode),
                decimal(clearinghouseStateNode.path("withdrawable")),
                decimal(clearinghouseStateNode.path("marginSummary").path("totalNtlPos")),
                decimal(clearinghouseStateNode.path("marginSummary").path("totalMarginUsed")),
                parsePortfolioWindows(portfolioNode),
                parseAccountValueHistory(portfolioNode),
                parseSpotBalances(spotStateNode, spotPrices),
                parsePerpPositions(clearinghouseStateNode),
                parseVaultEquities(vaultEquitiesNode));

        return new HyperliquidReport(summary, exchanges);
    }

    private JsonNode query(String type, Map<String, Object> body, List<HyperliquidExchange> exchanges) {
        HyperliquidInfoClient.CallResult callResult = client.query(type, body);
        exchanges.add(new HyperliquidExchange(
                type,
                callResult.requestBody(),
                callResult.responseBody().toPrettyString()));
        return callResult.responseBody();
    }

    private List<HyperliquidPortfolioWindow> parsePortfolioWindows(JsonNode portfolioNode) {
        List<HyperliquidPortfolioWindow> windows = new ArrayList<>();
        for (JsonNode periodNode : portfolioNode) {
            String period = periodNode.path(0).asText();
            if (!PORTFOLIO_PERIODS.contains(period)) {
                continue;
            }
            JsonNode payload = periodNode.path(1);
            windows.add(new HyperliquidPortfolioWindow(
                    period,
                    lastHistoryValue(payload.path("accountValueHistory")),
                    lastHistoryValue(payload.path("pnlHistory")),
                    payload.path("accountValueHistory").size(),
                    payload.path("pnlHistory").size()));
        }
        return windows;
    }

    private List<HyperliquidHistoryPoint> parseAccountValueHistory(JsonNode portfolioNode) {
        Map<LocalDate, HyperliquidHistoryPoint> byDate = new TreeMap<>();
        for (JsonNode periodNode : portfolioNode) {
            String period = periodNode.path(0).asText();
            if (!PORTFOLIO_PERIODS.contains(period)) {
                continue;
            }
            for (JsonNode historyNode : periodNode.path(1).path("accountValueHistory")) {
                HyperliquidHistoryPoint point = parseHistoryPoint(historyNode, period);
                if (point == null) {
                    continue;
                }
                byDate.merge(point.localDate(), point, (left, right) ->
                        right.timestamp().isAfter(left.timestamp()) ? right : left);
            }
        }
        return List.copyOf(byDate.values());
    }

    private BigDecimal latestPortfolioValue(JsonNode portfolioNode, String period) {
        for (JsonNode periodNode : portfolioNode) {
            if (period.equals(periodNode.path(0).asText())) {
                return lastHistoryValue(periodNode.path(1).path("accountValueHistory"));
            }
        }
        return BigDecimal.ZERO;
    }

    private BigDecimal lastHistoryValue(JsonNode historyNode) {
        if (!historyNode.isArray() || historyNode.isEmpty()) {
            return BigDecimal.ZERO;
        }
        JsonNode last = historyNode.get(historyNode.size() - 1);
        return decimal(last.path(1));
    }

    private HyperliquidHistoryPoint parseHistoryPoint(JsonNode historyNode, String period) {
        if (!historyNode.isArray() || historyNode.size() < 2) {
            return null;
        }
        Instant timestamp = parseTimestamp(historyNode.get(0));
        BigDecimal value = decimalOrNull(historyNode.get(1));
        if (timestamp == null || value == null) {
            return null;
        }
        return new HyperliquidHistoryPoint(
                timestamp,
                timestamp.atZone(ZoneOffset.UTC).toLocalDate(),
                value.setScale(2, RoundingMode.HALF_UP),
                period);
    }

    private Instant parseTimestamp(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String text = node.asText(null);
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            long numeric = Long.parseLong(text);
            if (Math.abs(numeric) < 10_000_000_000L) {
                numeric *= 1000;
            }
            return Instant.ofEpochMilli(numeric);
        } catch (NumberFormatException ignored) {
            try {
                return Instant.parse(text);
            } catch (RuntimeException exception) {
                return null;
            }
        }
    }

    private List<HyperliquidSpotBalance> parseSpotBalances(JsonNode spotStateNode, Map<Integer, BigDecimal> spotPrices) {
        List<HyperliquidSpotBalance> balances = new ArrayList<>();
        for (JsonNode balanceNode : spotStateNode.path("balances")) {
            int token = balanceNode.path("token").asInt();
            BigDecimal total = decimal(balanceNode.path("total"));
            BigDecimal priceUsd = spotPrices.get(token);
            BigDecimal valueUsd = priceUsd == null ? null : total.multiply(priceUsd).setScale(2, RoundingMode.HALF_UP);
            balances.add(new HyperliquidSpotBalance(
                    balanceNode.path("coin").asText(),
                    token,
                    total,
                    decimal(balanceNode.path("hold")),
                    decimal(balanceNode.path("entryNtl")),
                    priceUsd,
                    valueUsd));
        }
        return balances.stream()
                .sorted(Comparator.comparing(HyperliquidSpotBalance::valueUsd, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .toList();
    }

    private BigDecimal sumSpotValue(JsonNode spotStateNode, Map<Integer, BigDecimal> spotPrices) {
        return parseSpotBalances(spotStateNode, spotPrices).stream()
                .map(HyperliquidSpotBalance::valueUsd)
                .filter(value -> value != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private Map<Integer, BigDecimal> buildSpotPrices(JsonNode spotMetaNode) {
        Map<Integer, BigDecimal> prices = new HashMap<>();
        prices.put(0, BigDecimal.ONE);

        if (!spotMetaNode.isArray() || spotMetaNode.size() < 2) {
            return prices;
        }

        JsonNode metaNode = spotMetaNode.get(0);
        JsonNode contextsNode = spotMetaNode.get(1);

        Map<Integer, JsonNode> contextsByIndex = new HashMap<>();
        for (int index = 0; index < contextsNode.size(); index++) {
            contextsByIndex.put(index, contextsNode.get(index));
        }

        for (JsonNode universeNode : metaNode.path("universe")) {
            JsonNode tokensNode = universeNode.path("tokens");
            if (!tokensNode.isArray() || tokensNode.size() < 2) {
                continue;
            }
            int baseToken = tokensNode.get(0).asInt(-1);
            int quoteToken = tokensNode.get(1).asInt(-1);
            if (quoteToken != 0 || baseToken < 0) {
                continue;
            }
            JsonNode contextNode = contextsByIndex.get(universeNode.path("index").asInt(-1));
            if (contextNode == null) {
                continue;
            }
            BigDecimal price = decimalOrNull(contextNode.path("markPx"));
            if (price == null) {
                price = decimalOrNull(contextNode.path("midPx"));
            }
            if (price != null) {
                prices.putIfAbsent(baseToken, price);
            }
        }

        return prices;
    }

    private List<HyperliquidPerpPosition> parsePerpPositions(JsonNode clearinghouseStateNode) {
        List<HyperliquidPerpPosition> positions = new ArrayList<>();
        for (JsonNode positionNode : clearinghouseStateNode.path("assetPositions")) {
            JsonNode inner = positionNode.path("position");
            positions.add(new HyperliquidPerpPosition(
                    inner.path("coin").asText(),
                    decimal(inner.path("szi")),
                    decimal(inner.path("positionValue")),
                    decimal(inner.path("entryPx")),
                    decimal(inner.path("unrealizedPnl")),
                    decimal(inner.path("returnOnEquity")),
                    decimal(inner.path("liquidationPx")),
                    decimal(inner.path("leverage").path("value"))));
        }
        return positions.stream()
                .sorted(Comparator.comparing(HyperliquidPerpPosition::positionValue, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .toList();
    }

    private List<HyperliquidVaultEquity> parseVaultEquities(JsonNode vaultEquitiesNode) {
        List<HyperliquidVaultEquity> vaults = new ArrayList<>();
        for (JsonNode vaultNode : vaultEquitiesNode) {
            vaults.add(new HyperliquidVaultEquity(
                    vaultNode.path("vaultAddress").asText(),
                    decimal(vaultNode.path("equity"))));
        }
        return vaults.stream()
                .sorted(Comparator.comparing(HyperliquidVaultEquity::equity).reversed())
                .toList();
    }

    private BigDecimal sumVaultValue(JsonNode vaultEquitiesNode) {
        return parseVaultEquities(vaultEquitiesNode).stream()
                .map(HyperliquidVaultEquity::equity)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal decimal(JsonNode node) {
        BigDecimal value = decimalOrNull(node);
        return value == null ? BigDecimal.ZERO : value;
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
}
