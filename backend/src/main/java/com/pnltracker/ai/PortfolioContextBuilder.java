package com.pnltracker.ai;

import com.pnltracker.analytics.PortfolioMetrics;
import com.pnltracker.analytics.PortfolioMetricsCalculator;
import com.pnltracker.defi.model.DefiPosition;
import com.pnltracker.domain.AssetBalance;
import com.pnltracker.domain.ChainAllocation;
import com.pnltracker.domain.PortfolioAnalysis;
import com.pnltracker.market.StableYieldMarket;
import com.pnltracker.market.StableYieldMarketService;
import com.pnltracker.service.PortfolioOverviewService;
import com.pnltracker.service.StablecoinSymbols;
import com.pnltracker.solana.defi.model.SolanaTokenizedDefiPosition;
import com.pnltracker.solana.defi.service.SolanaTokenizedDefiDetectionService;
import com.pnltracker.solana.worker.SolanaDefiWorkerClient;
import com.pnltracker.solana.worker.dto.WorkerPosition;
import com.pnltracker.solana.worker.dto.WorkerPositionToken;
import com.pnltracker.solana.worker.dto.WorkerPositionsResponse;
import com.pnltracker.zerion.ZerionDeFiService;
import com.pnltracker.zerion.ZerionPosition;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class PortfolioContextBuilder {

    private static final double MIN_DEFI_POSITION_USD = 1.0d;

    private final PortfolioOverviewService portfolioOverviewService;
    private final ZerionDeFiService zerionDeFiService;
    private final SolanaDefiWorkerClient solanaDefiWorkerClient;
    private final SolanaTokenizedDefiDetectionService solanaTokenizedDefiDetectionService;
    private final PortfolioMetricsCalculator portfolioMetricsCalculator;
    private final StableYieldMarketService stableYieldMarketService;

    public PortfolioContextBuilder(
            PortfolioOverviewService portfolioOverviewService,
            ZerionDeFiService zerionDeFiService,
            SolanaDefiWorkerClient solanaDefiWorkerClient,
            SolanaTokenizedDefiDetectionService solanaTokenizedDefiDetectionService,
            PortfolioMetricsCalculator portfolioMetricsCalculator,
            StableYieldMarketService stableYieldMarketService) {
        this.portfolioOverviewService = portfolioOverviewService;
        this.zerionDeFiService = zerionDeFiService;
        this.solanaDefiWorkerClient = solanaDefiWorkerClient;
        this.solanaTokenizedDefiDetectionService = solanaTokenizedDefiDetectionService;
        this.portfolioMetricsCalculator = portfolioMetricsCalculator;
        this.stableYieldMarketService = stableYieldMarketService;
    }

    public PortfolioContext build(List<String> addresses, List<String> chains) {
        PortfolioAnalysis overview = portfolioOverviewService.getOverview(addresses, chains);
        List<AssetBalance> sortedAssets = overview.assets().stream()
                .sorted(Comparator.comparing(AssetBalance::valueUsd).reversed())
                .toList();

        List<AiDefiPosition> defiPositions = defiPositions(addresses);
        double solanaAdditionalDefiUsd = defiPositions.stream()
                .filter(position -> isSolana(position.chain()))
                .filter(position -> !position.alreadyCountedInSpotTotals())
                .mapToDouble(AiDefiPosition::valueUsd)
                .sum();

        double totalValueUsd = toDouble(overview.summary().totalUsd()) + solanaAdditionalDefiUsd;
        List<ChainAllocation> chainAllocations = chainAllocations(overview.summary().allocations(), solanaAdditionalDefiUsd);
        List<TokenExposure> tokenExposures = tokenExposures(sortedAssets, defiPositions, totalValueUsd);

        double spotStableUsd = sortedAssets.stream()
                .filter(asset -> StablecoinSymbols.isStable(asset.symbol()))
                .map(AssetBalance::valueUsd)
                .mapToDouble(this::toDouble)
                .sum();
        double deployedStableUsd = overview.defiPositions().stream()
                .filter(position -> StablecoinSymbols.isStable(position.underlyingSymbol()))
                .map(DefiPosition::grossSupplyUsd)
                .mapToDouble(this::toDouble)
                .sum();
        double deployedStableAlreadyCountedUsd = overview.defiPositions().stream()
                .filter(DefiPosition::alreadyCountedInPortfolio)
                .filter(position -> StablecoinSymbols.isStable(position.underlyingSymbol()))
                .map(DefiPosition::grossSupplyUsd)
                .mapToDouble(this::toDouble)
                .sum();
        double solanaDeployedStableUsd = defiPositions.stream()
                .filter(position -> isSolana(position.chain()))
                .filter(position -> StablecoinSymbols.isStable(position.tokenSymbol()))
                .mapToDouble(AiDefiPosition::valueUsd)
                .sum();
        double solanaStableAlreadyCountedUsd = defiPositions.stream()
                .filter(position -> isSolana(position.chain()))
                .filter(AiDefiPosition::alreadyCountedInSpotTotals)
                .filter(position -> StablecoinSymbols.isStable(position.tokenSymbol()))
                .mapToDouble(AiDefiPosition::valueUsd)
                .sum();

        double stableUsd = spotStableUsd
                + Math.max(deployedStableUsd - deployedStableAlreadyCountedUsd, 0.0d)
                + Math.max(solanaDeployedStableUsd - solanaStableAlreadyCountedUsd, 0.0d);
        double totalDefiValueUsd = defiPositions.stream()
                .mapToDouble(AiDefiPosition::valueUsd)
                .sum();
        double idleStableUsd = Math.max(spotStableUsd - deployedStableAlreadyCountedUsd - solanaStableAlreadyCountedUsd, 0.0d);

        TokenExposure largest = largestNonStableExposure(tokenExposures);
        String largestSymbol = largest == null ? "N/A" : nullSafe(largest.symbol());
        double largestValueUsd = largest == null ? 0.0d : largest.totalUsd();
        PortfolioMetrics riskMetrics = riskMetrics(addresses, chains);
        StableYieldMarket stableYieldMarket = stableYieldMarketService.conservativeStableLending(idleStableUsd);

        return new PortfolioContext(
                totalValueUsd,
                walletCount(addresses),
                chainAllocations,
                tokenExposures,
                sortedAssets.stream().limit(10).toList(),
                defiPositions,
                totalDefiValueUsd,
                stableUsd,
                deployedStableUsd + solanaDeployedStableUsd,
                pct(stableUsd, totalValueUsd),
                pct(totalDefiValueUsd, totalValueUsd),
                largestSymbol,
                pct(largestValueUsd, totalValueUsd),
                idleStableUsd,
                riskMetrics,
                stableYieldMarket);
    }

    private List<AiDefiPosition> defiPositions(List<String> addresses) {
        List<AiDefiPosition> positions = new ArrayList<>();
        evmAddresses(addresses).forEach(address -> positions.addAll(evmDefiPositions(address)));
        solanaAddresses(addresses).forEach(address -> positions.addAll(solanaDefiPositions(address)));
        return positions.stream()
                .filter(position -> position.valueUsd() >= MIN_DEFI_POSITION_USD)
                .sorted(Comparator.comparingDouble(AiDefiPosition::valueUsd).reversed())
                .toList();
    }

    private List<AiDefiPosition> evmDefiPositions(String address) {
        try {
            return zerionDeFiService.getPositions(address).positions().stream()
                    .map(this::toAiDefiPosition)
                    .filter(position -> position.valueUsd() >= MIN_DEFI_POSITION_USD)
                    .toList();
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    private AiDefiPosition toAiDefiPosition(ZerionPosition position) {
        double valueUsd = position.value() == null ? 0.0d : position.value();
        return new AiDefiPosition(
                normalizeChain(firstPresent(position.chain(), position.chainId(), "evm")),
                firstPresent(position.protocolName(), position.protocol(), position.dapp(), "Unknown protocol"),
                blankToNull(position.protocolModule()),
                firstPresent(position.positionType(), "position"),
                canonicalSymbol(position.tokenSymbol()),
                valueUsd,
                valueUsd,
                0.0d,
                true);
    }

    private List<AiDefiPosition> solanaDefiPositions(String address) {
        List<AiDefiPosition> workerPositions = solanaWorkerPositions(address);
        if (!workerPositions.isEmpty()) {
            return workerPositions;
        }
        return solanaTokenizedPositions(address);
    }

    private List<AiDefiPosition> solanaWorkerPositions(String address) {
        try {
            WorkerPositionsResponse response = solanaDefiWorkerClient.fetchPositions(address);
            boolean workerUnavailable = response.errors() != null
                    && response.errors().stream().anyMatch(error -> "worker".equalsIgnoreCase(error.protocolId()));
            if (workerUnavailable) {
                return List.of();
            }

            return response.positions() == null
                    ? List.of()
                    : response.positions().stream()
                            .flatMap(position -> workerPositions(position).stream())
                            .filter(position -> position.valueUsd() >= MIN_DEFI_POSITION_USD)
                            .toList();
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    private List<AiDefiPosition> workerPositions(WorkerPosition position) {
        List<AiDefiPosition> positions = new ArrayList<>();
        if (position.tokens() != null) {
            position.tokens().forEach(token -> positions.add(toAiDefiPosition(position, token, position.positionType())));
        }
        if (position.pendingRewards() != null) {
            position.pendingRewards().forEach(token -> positions.add(toAiDefiPosition(position, token, "reward")));
        }
        return positions;
    }

    private AiDefiPosition toAiDefiPosition(WorkerPosition position, WorkerPositionToken token, String positionType) {
        double valueUsd = Math.max(token.valueUsd(), 0.0d);
        return new AiDefiPosition(
                "solana",
                firstPresent(position.protocolName(), position.protocolId(), "Unknown protocol"),
                blankToNull(position.category()),
                firstPresent(positionType, "position"),
                canonicalSymbol(token.symbol()),
                valueUsd,
                valueUsd,
                0.0d,
                false);
    }

    private List<AiDefiPosition> solanaTokenizedPositions(String address) {
        try {
            return solanaTokenizedDefiDetectionService.detect(address).stream()
                    .map(this::toAiDefiPosition)
                    .filter(position -> position.valueUsd() >= MIN_DEFI_POSITION_USD)
                    .toList();
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    private AiDefiPosition toAiDefiPosition(SolanaTokenizedDefiPosition position) {
        double valueUsd = toDouble(position.valueUsd());
        return new AiDefiPosition(
                "solana",
                firstPresent(position.protocolName(), position.protocolKey(), "Unknown protocol"),
                blankToNull(position.source()),
                firstPresent(position.positionType(), "position"),
                canonicalSymbol(position.symbol()),
                valueUsd,
                valueUsd,
                0.0d,
                position.alreadyCountedInSpotTotals());
    }

    private List<ChainAllocation> chainAllocations(List<ChainAllocation> allocations, double solanaAdditionalDefiUsd) {
        Map<String, ChainAllocation> rows = new LinkedHashMap<>();
        for (ChainAllocation allocation : allocations) {
            rows.put(normalizeChain(allocation.network()), new ChainAllocation(
                    normalizeChain(allocation.network()),
                    allocation.displayName(),
                    allocation.valueUsd()));
        }
        if (solanaAdditionalDefiUsd > 0.0d) {
            String key = "solana";
            ChainAllocation existing = rows.get(key);
            BigDecimal valueUsd = (existing == null ? BigDecimal.ZERO : safeAmount(existing.valueUsd()))
                    .add(money(solanaAdditionalDefiUsd));
            rows.put(key, new ChainAllocation(
                    key,
                    existing == null ? "Solana" : existing.displayName(),
                    valueUsd));
        }
        return rows.values().stream()
                .sorted(Comparator.comparing(ChainAllocation::valueUsd).reversed())
                .toList();
    }

    private List<TokenExposure> tokenExposures(
            List<AssetBalance> assets,
            List<AiDefiPosition> defiPositions,
            double totalValueUsd) {
        Map<String, MutableTokenExposure> rows = new LinkedHashMap<>();
        for (AssetBalance asset : assets) {
            String symbol = canonicalSymbol(asset.symbol());
            rows.computeIfAbsent(symbol, MutableTokenExposure::new).spotUsd += toDouble(asset.valueUsd());
        }
        for (AiDefiPosition position : defiPositions) {
            String symbol = canonicalSymbol(position.tokenSymbol());
            MutableTokenExposure row = rows.computeIfAbsent(symbol, MutableTokenExposure::new);
            row.defiUsd += position.valueUsd();
            if (!position.alreadyCountedInSpotTotals()) {
                row.uncountedDefiUsd += position.valueUsd();
            }
        }
        return rows.values().stream()
                .map(row -> row.toExposure(totalValueUsd))
                .filter(row -> row.totalUsd() > 0.0d || row.defiUsd() > 0.0d)
                .sorted(Comparator.comparingDouble(TokenExposure::totalUsd).reversed())
                .toList();
    }

    private TokenExposure largestNonStableExposure(List<TokenExposure> tokenExposures) {
        return tokenExposures.stream()
                .filter(exposure -> !StablecoinSymbols.isStable(exposure.symbol()))
                .findFirst()
                .orElse(null);
    }

    private PortfolioMetrics riskMetrics(List<String> addresses, List<String> chains) {
        try {
            return portfolioMetricsCalculator.calculate(addresses, chains);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private List<String> evmAddresses(List<String> addresses) {
        if (addresses == null) {
            return List.of();
        }
        return addresses.stream()
                .map(address -> address == null ? "" : address.trim())
                .filter(this::isEvmAddress)
                .map(address -> address.toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    private List<String> solanaAddresses(List<String> addresses) {
        if (addresses == null) {
            return List.of();
        }
        return addresses.stream()
                .map(address -> address == null ? "" : address.trim())
                .filter(this::isSolanaAddress)
                .distinct()
                .toList();
    }

    private int walletCount(List<String> addresses) {
        if (addresses == null) {
            return 0;
        }
        return (int) addresses.stream()
                .map(address -> address == null ? "" : address.trim())
                .filter(address -> !address.isBlank())
                .distinct()
                .count();
    }

    private boolean isEvmAddress(String value) {
        return value != null && value.matches("(?i)^0x[0-9a-f]{40}$");
    }

    private boolean isSolanaAddress(String value) {
        return value != null && value.matches("^[1-9A-HJ-NP-Za-km-z]{32,44}$");
    }

    private boolean isSolana(String chain) {
        return "solana".equals(normalizeChain(chain));
    }

    private String normalizeChain(String chain) {
        String value = chain == null ? "" : chain.trim().toLowerCase(Locale.ROOT);
        return value.replaceAll("-mainnet$", "");
    }

    private String canonicalSymbol(String symbol) {
        String value = symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
        if ("USDC.E".equals(value)) {
            return "USDC";
        }
        if ("WETH".equals(value)) {
            return "ETH";
        }
        return value.isBlank() ? "UNKNOWN" : value;
    }

    private double pct(double value, double total) {
        if (total <= 0.0d) {
            return 0.0d;
        }
        return value / total * 100.0d;
    }

    private double toDouble(BigDecimal value) {
        return value == null ? 0.0d : value.doubleValue();
    }

    private BigDecimal safeAmount(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal money(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    private String nullSafe(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value;
    }

    private String firstPresent(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static final class MutableTokenExposure {
        private final String symbol;
        private double spotUsd;
        private double defiUsd;
        private double uncountedDefiUsd;

        private MutableTokenExposure(String symbol) {
            this.symbol = symbol;
        }

        private TokenExposure toExposure(double portfolioTotalUsd) {
            double totalUsd = spotUsd + uncountedDefiUsd;
            return new TokenExposure(
                    symbol,
                    totalUsd,
                    spotUsd,
                    defiUsd,
                    portfolioTotalUsd <= 0.0d ? 0.0d : totalUsd / portfolioTotalUsd * 100.0d);
        }
    }
}
