package com.pnltracker.analytics;

import com.pnltracker.defi.model.DefiPosition;
import com.pnltracker.domain.AssetBalance;
import com.pnltracker.domain.ChainDefinition;
import com.pnltracker.domain.PortfolioAnalysis;
import com.pnltracker.service.ChainCatalogService;
import com.pnltracker.service.GoldRushPortfolioHistoryProvider;
import com.pnltracker.service.PortfolioHistoryFetchRequest;
import com.pnltracker.service.PortfolioHistoryFetchResult;
import com.pnltracker.service.PortfolioHistoryPeriod;
import com.pnltracker.service.PortfolioOverviewService;
import com.pnltracker.service.StablecoinSymbols;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class PortfolioMetricsCalculator {

    private static final double ANNUAL_RISK_FREE_RATE = 0.05d;
    private static final double TRADING_DAYS_PER_YEAR = 252.0d;
    private static final int MIN_HISTORY_POINTS = 14;

    private final PortfolioOverviewService portfolioOverviewService;
    private final GoldRushPortfolioHistoryProvider goldRushPortfolioHistoryProvider;
    private final ChainCatalogService chainCatalogService;

    public PortfolioMetricsCalculator(
            PortfolioOverviewService portfolioOverviewService,
            GoldRushPortfolioHistoryProvider goldRushPortfolioHistoryProvider,
            ChainCatalogService chainCatalogService) {
        this.portfolioOverviewService = portfolioOverviewService;
        this.goldRushPortfolioHistoryProvider = goldRushPortfolioHistoryProvider;
        this.chainCatalogService = chainCatalogService;
    }

    public PortfolioMetrics calculate(List<String> addresses, List<String> chains) {
        PortfolioAnalysis overview = portfolioOverviewService.getOverview(addresses, chains);
        double totalUsd = amount(overview.summary().totalUsd());
        AssetBalance largest = overview.assets().stream()
                .max(Comparator.comparing(AssetBalance::valueUsd))
                .orElse(null);
        double largestUsd = largest == null ? 0.0d : amount(largest.valueUsd());
        double concentrationPct = pct(largestUsd, totalUsd);

        double stableUsd = overview.assets().stream()
                .filter(asset -> StablecoinSymbols.isStable(asset.symbol()))
                .map(AssetBalance::valueUsd)
                .mapToDouble(this::amount)
                .sum();
        double stableInDefiUsd = overview.defiPositions().stream()
                .filter(position -> StablecoinSymbols.isStable(position.underlyingSymbol()))
                .map(DefiPosition::grossSupplyUsd)
                .mapToDouble(this::amount)
                .sum();
        double defiUsd = amount(overview.defiSummary().visibleNetUsd());
        double idleStableUsd = Math.max(stableUsd - stableInDefiUsd, 0.0d);

        List<Double> values = goldRushValues(addresses, chains);
        TimeSeriesMetrics timeSeries = values.size() < MIN_HISTORY_POINTS
                ? TimeSeriesMetrics.empty(values.size())
                : calculateTimeSeries(values);

        return new PortfolioMetrics(
                concentrationPct,
                largest == null ? "N/A" : largest.symbol(),
                concentrationRisk(concentrationPct),
                pct(stableUsd, totalUsd),
                pct(defiUsd, totalUsd),
                idleStableUsd,
                idleStableUsd * ANNUAL_RISK_FREE_RATE / 12.0d,
                timeSeries.sharpe30d(),
                timeSeries.sortino30d(),
                timeSeries.maxDrawdownPct30d(),
                timeSeries.historyDaysAvailable());
    }

    private List<Double> goldRushValues(List<String> addresses, List<String> chains) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Set<LocalDate> requiredDates = new LinkedHashSet<>();
        for (LocalDate date = today.minusDays(PortfolioHistoryPeriod.D30.lookbackDays());
                !date.isAfter(today);
                date = date.plusDays(1)) {
            requiredDates.add(date);
        }

        List<String> normalizedAddresses = addresses.stream()
                .map(String::trim)
                .filter(address -> !address.isBlank())
                .distinct()
                .toList();
        List<ChainDefinition> resolvedChains = chainCatalogService.resolve(chains);
        PortfolioHistoryFetchResult result = goldRushPortfolioHistoryProvider.fetchHistory(new PortfolioHistoryFetchRequest(
                normalizedAddresses,
                resolvedChains,
                requiredDates,
                PortfolioHistoryPeriod.D30));

        return result.totalsByDate().entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .map(entry -> amount(entry.getValue()))
                .toList();
    }

    private TimeSeriesMetrics calculateTimeSeries(List<Double> values) {
        List<Double> returns = dailyReturns(values);
        if (returns.isEmpty()) {
            return TimeSeriesMetrics.empty(values.size());
        }

        double mean = mean(returns);
        double stddev = stddev(returns);
        double riskFreeDaily = ANNUAL_RISK_FREE_RATE / 365.0d;
        Double sharpe = stddev == 0.0d
                ? null
                : (mean - riskFreeDaily) / stddev * Math.sqrt(TRADING_DAYS_PER_YEAR);

        List<Double> downsideReturns = returns.stream()
                .filter(value -> value < 0.0d)
                .toList();
        double downsideDeviation = stddev(downsideReturns);
        Double sortino = downsideReturns.isEmpty() || downsideDeviation == 0.0d
                ? null
                : (mean - riskFreeDaily) / downsideDeviation * Math.sqrt(TRADING_DAYS_PER_YEAR);

        return new TimeSeriesMetrics(
                sharpe,
                sortino,
                maxDrawdownPct(values),
                values.size());
    }

    private List<Double> dailyReturns(List<Double> values) {
        java.util.ArrayList<Double> returns = new java.util.ArrayList<>();
        for (int i = 1; i < values.size(); i++) {
            double previous = values.get(i - 1);
            double current = values.get(i);
            if (previous > 0.0d) {
                returns.add((current - previous) / previous);
            }
        }
        return List.copyOf(returns);
    }

    private double maxDrawdownPct(List<Double> values) {
        double peak = values.get(0);
        double maxDrawdown = 0.0d;
        for (double value : values) {
            if (value > peak) {
                peak = value;
            }
            if (peak <= 0.0d) {
                continue;
            }
            double drawdown = (peak - value) / peak;
            if (drawdown > maxDrawdown) {
                maxDrawdown = drawdown;
            }
        }
        return -maxDrawdown * 100.0d;
    }

    private String concentrationRisk(double concentrationPct) {
        if (concentrationPct > 50.0d) {
            return "CRITICAL";
        }
        if (concentrationPct >= 30.0d) {
            return "HIGH";
        }
        if (concentrationPct >= 15.0d) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private double mean(List<Double> values) {
        if (values.isEmpty()) {
            return 0.0d;
        }
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0d);
    }

    private double stddev(List<Double> values) {
        if (values.isEmpty()) {
            return 0.0d;
        }
        double mean = mean(values);
        double variance = values.stream()
                .mapToDouble(value -> Math.pow(value - mean, 2.0d))
                .average()
                .orElse(0.0d);
        return Math.sqrt(variance);
    }

    private double pct(double value, double total) {
        return total <= 0.0d ? 0.0d : value / total * 100.0d;
    }

    private double amount(BigDecimal value) {
        return value == null ? 0.0d : value.doubleValue();
    }

    private record TimeSeriesMetrics(
            Double sharpe30d,
            Double sortino30d,
            Double maxDrawdownPct30d,
            int historyDaysAvailable) {

        static TimeSeriesMetrics empty(int historyDaysAvailable) {
            return new TimeSeriesMetrics(null, null, null, historyDaysAvailable);
        }
    }
}
