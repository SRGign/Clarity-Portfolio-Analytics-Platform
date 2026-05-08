package com.pnltracker.ai;

import com.pnltracker.domain.AssetBalance;
import com.pnltracker.domain.PortfolioAnalysis;
import com.pnltracker.service.PortfolioOverviewService;
import com.pnltracker.service.StablecoinSymbols;
import com.pnltracker.market.StableYieldMarket;
import com.pnltracker.market.StableYieldMarketService;
import com.pnltracker.zerion.ZerionDeFiService;
import com.pnltracker.zerion.ZerionPosition;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
public class PortfolioContextBuilder {

    private final PortfolioOverviewService portfolioOverviewService;
    private final ZerionDeFiService zerionDeFiService;
    private final StableYieldMarketService stableYieldMarketService;

    public PortfolioContextBuilder(
            PortfolioOverviewService portfolioOverviewService,
            ZerionDeFiService zerionDeFiService,
            StableYieldMarketService stableYieldMarketService) {
        this.portfolioOverviewService = portfolioOverviewService;
        this.zerionDeFiService = zerionDeFiService;
        this.stableYieldMarketService = stableYieldMarketService;
    }

    public PortfolioContext build(List<String> addresses, List<String> chains) {
        PortfolioAnalysis overview = portfolioOverviewService.getOverview(addresses, chains);
        double totalValueUsd = toDouble(overview.summary().totalUsd());
        List<AssetBalance> sortedAssets = overview.assets().stream()
                .sorted(Comparator.comparing(AssetBalance::valueUsd).reversed())
                .toList();

        List<ZerionPosition> evmDefiPositions = firstEvmAddress(addresses)
                .map(address -> zerionDeFiService.getPositions(address).positions())
                .orElse(List.of());

        double stableUsd = sortedAssets.stream()
                .filter(asset -> StablecoinSymbols.isStable(asset.symbol()))
                .map(AssetBalance::valueUsd)
                .mapToDouble(this::toDouble)
                .sum();
        double totalDefiValueUsd = evmDefiPositions.stream()
                .map(ZerionPosition::value)
                .filter(value -> value != null && value > 0.0d)
                .mapToDouble(Double::doubleValue)
                .sum();
        double stableDefiUsd = evmDefiPositions.stream()
                .filter(position -> "deposit".equalsIgnoreCase(nullSafe(position.positionType())))
                .filter(position -> StablecoinSymbols.isStable(position.tokenSymbol()))
                .map(ZerionPosition::value)
                .filter(value -> value != null && value > 0.0d)
                .mapToDouble(Double::doubleValue)
                .sum();

        AssetBalance largest = sortedAssets.isEmpty() ? null : sortedAssets.get(0);
        double largestValueUsd = largest == null ? 0.0d : toDouble(largest.valueUsd());
        String largestSymbol = largest == null ? "N/A" : nullSafe(largest.symbol());
        double idleStableUsd = Math.max(stableUsd - stableDefiUsd, 0.0d);
        StableYieldMarket stableYieldMarket = stableYieldMarketService.conservativeStableLending(idleStableUsd);

        return new PortfolioContext(
                totalValueUsd,
                walletCount(addresses),
                overview.summary().allocations(),
                sortedAssets.stream().limit(10).toList(),
                evmDefiPositions,
                totalDefiValueUsd,
                pct(stableUsd, totalValueUsd),
                pct(totalDefiValueUsd, totalValueUsd),
                largestSymbol,
                pct(largestValueUsd, totalValueUsd),
                idleStableUsd,
                stableYieldMarket);
    }

    private java.util.Optional<String> firstEvmAddress(List<String> addresses) {
        if (addresses == null) {
            return java.util.Optional.empty();
        }
        return addresses.stream()
                .map(address -> address == null ? "" : address.trim())
                .filter(address -> address.matches("(?i)^0x[0-9a-f]{40}$"))
                .map(address -> address.toLowerCase(Locale.ROOT))
                .findFirst();
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

    private double pct(double value, double total) {
        if (total <= 0.0d) {
            return 0.0d;
        }
        return value / total * 100.0d;
    }

    private double toDouble(BigDecimal value) {
        return value == null ? 0.0d : value.doubleValue();
    }

    private String nullSafe(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value;
    }
}
