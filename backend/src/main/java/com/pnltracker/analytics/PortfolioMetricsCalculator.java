package com.pnltracker.analytics;

import com.pnltracker.defi.model.DefiPosition;
import com.pnltracker.domain.AssetBalance;
import com.pnltracker.domain.PortfolioAnalysis;
import com.pnltracker.service.PortfolioHistoryPeriod;
import com.pnltracker.service.PortfolioHistoryResultPoint;
import com.pnltracker.service.PortfolioHistoryService;
import com.pnltracker.service.PortfolioOverviewService;
import com.pnltracker.service.StablecoinSymbols;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class PortfolioMetricsCalculator {

    private static final double ANNUAL_RISK_FREE_RATE = 0.05d;
    private static final double TRADING_DAYS_PER_YEAR = 252.0d;
    private static final int MIN_HISTORY_POINTS = 14;
    private static final Set<String> CORE_MAJOR_SYMBOLS = Set.of(
            "BTC",
            "WBTC",
            "CBBTC",
            "TBTC",
            "RENBTC",
            "XBT",
            "ETH",
            "WETH",
            "WETHE",
            "STETH",
            "WSTETH",
            "RETH",
            "CBETH",
            "FRXETH",
            "SFRXETH",
            "METH",
            "WEETH",
            "EZETH",
            "OSETH",
            "SWETH");

    private final PortfolioOverviewService portfolioOverviewService;
    private final PortfolioHistoryService portfolioHistoryService;
    private final Clock clock;

    @Autowired
    public PortfolioMetricsCalculator(
            PortfolioOverviewService portfolioOverviewService,
            PortfolioHistoryService portfolioHistoryService) {
        this(portfolioOverviewService, portfolioHistoryService, Clock.systemUTC());
    }

    PortfolioMetricsCalculator(
            PortfolioOverviewService portfolioOverviewService,
            PortfolioHistoryService portfolioHistoryService,
            Clock clock) {
        this.portfolioOverviewService = portfolioOverviewService;
        this.portfolioHistoryService = portfolioHistoryService;
        this.clock = clock;
    }

    public PortfolioMetrics calculate(List<String> addresses, List<String> chains) {
        PortfolioAnalysis overview = portfolioOverviewService.getOverview(addresses, chains);
        double totalUsd = amount(overview.summary().totalUsd());
        AssetBalance largest = overview.assets().stream()
                .filter(asset -> !StablecoinSymbols.isStable(asset.symbol()))
                .max(Comparator.comparing(AssetBalance::valueUsd))
                .orElse(null);
        double largestUsd = largest == null ? 0.0d : amount(largest.valueUsd());
        double concentrationPct = pct(largestUsd, totalUsd);

        double spotStableUsd = overview.assets().stream()
                .filter(asset -> StablecoinSymbols.isStable(asset.symbol()))
                .map(AssetBalance::valueUsd)
                .mapToDouble(this::amount)
                .sum();
        double deployedStableUsd = overview.defiPositions().stream()
                .filter(position -> StablecoinSymbols.isStable(position.underlyingSymbol()))
                .map(DefiPosition::grossSupplyUsd)
                .mapToDouble(this::amount)
                .sum();
        double deployedStableAlreadyCountedUsd = overview.defiPositions().stream()
                .filter(DefiPosition::alreadyCountedInPortfolio)
                .filter(position -> StablecoinSymbols.isStable(position.underlyingSymbol()))
                .map(DefiPosition::grossSupplyUsd)
                .mapToDouble(this::amount)
                .sum();
        double stableUsd = spotStableUsd + Math.max(deployedStableUsd - deployedStableAlreadyCountedUsd, 0.0d);
        double defiExposureUsd = overview.defiPositions().stream()
                .map(this::exposureUsd)
                .filter(value -> value != null)
                .mapToDouble(this::amount)
                .sum();
        double defiNetUsd = amount(overview.defiSummary().visibleNetUsd());
        double idleStableUsd = Math.max(spotStableUsd - deployedStableAlreadyCountedUsd, 0.0d);

        List<HistoryValue> values = historyValues(addresses, chains);
        TimeSeriesMetrics timeSeries = values.size() < MIN_HISTORY_POINTS
                ? TimeSeriesMetrics.empty(values)
                : calculateTimeSeries(values);

        return new PortfolioMetrics(
                totalUsd,
                concentrationPct,
                largestUsd,
                largest == null ? "N/A" : largest.symbol(),
                concentrationRisk(largest == null ? null : largest.symbol(), concentrationPct),
                pct(stableUsd, totalUsd),
                stableUsd,
                deployedStableUsd,
                pct(defiExposureUsd, totalUsd),
                defiExposureUsd,
                defiNetUsd,
                overview.defiPositions().size(),
                idleStableUsd,
                idleStableUsd * ANNUAL_RISK_FREE_RATE / 12.0d,
                timeSeries.sharpe30d(),
                timeSeries.sortino30d(),
                timeSeries.maxDrawdownPct30d(),
                timeSeries.averageDailyReturnPct30d(),
                timeSeries.dailyVolatilityPct30d(),
                timeSeries.downsideDeviationPct30d(),
                timeSeries.historyStartDate(),
                timeSeries.historyEndDate(),
                timeSeries.maxDrawdownPeakDate(),
                timeSeries.maxDrawdownTroughDate(),
                timeSeries.maxDrawdownPeakUsd(),
                timeSeries.maxDrawdownTroughUsd(),
                timeSeries.historyDaysAvailable());
    }

    private List<HistoryValue> historyValues(List<String> addresses, List<String> chains) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        return portfolioHistoryService.getHistory(addresses, chains, PortfolioHistoryPeriod.D30).points().stream()
                .sorted(Comparator.comparing(PortfolioHistoryResultPoint::localDate))
                .filter(point -> point.localDate().isBefore(today))
                .map(point -> new HistoryValue(point.localDate(), amount(point.totalUsd())))
                .toList();
    }

    private TimeSeriesMetrics calculateTimeSeries(List<HistoryValue> values) {
        List<Double> returns = dailyReturns(values.stream().map(HistoryValue::valueUsd).toList());
        if (returns.isEmpty()) {
            return TimeSeriesMetrics.empty(values);
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
        DrawdownPoint drawdown = maxDrawdown(values);

        return new TimeSeriesMetrics(
                sharpe,
                sortino,
                drawdown.maxDrawdownPct(),
                mean * 100.0d,
                stddev * 100.0d,
                downsideDeviation * 100.0d,
                values.get(0).date().toString(),
                values.get(values.size() - 1).date().toString(),
                drawdown.peakDate(),
                drawdown.troughDate(),
                drawdown.peakUsd(),
                drawdown.troughUsd(),
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

    private DrawdownPoint maxDrawdown(List<HistoryValue> values) {
        double peak = values.get(0).valueUsd();
        LocalDate peakDate = values.get(0).date();
        LocalDate drawdownPeakDate = peakDate;
        LocalDate troughDate = peakDate;
        double drawdownPeakUsd = peak;
        double troughUsd = peak;
        double maxDrawdown = 0.0d;
        for (HistoryValue point : values) {
            double value = point.valueUsd();
            if (value > peak) {
                peak = value;
                peakDate = point.date();
            }
            if (peak <= 0.0d) {
                continue;
            }
            double drawdown = (peak - value) / peak;
            if (drawdown > maxDrawdown) {
                maxDrawdown = drawdown;
                drawdownPeakDate = peakDate;
                troughDate = point.date();
                drawdownPeakUsd = peak;
                troughUsd = value;
            }
        }
        return new DrawdownPoint(
                -maxDrawdown * 100.0d,
                drawdownPeakDate.toString(),
                troughDate.toString(),
                drawdownPeakUsd,
                troughUsd);
    }

    private String concentrationRisk(String symbol, double concentrationPct) {
        if (isCoreMajorAsset(symbol)) {
            if (concentrationPct >= 80.0d) {
                return "HIGH";
            }
            if (concentrationPct >= 65.0d) {
                return "MEDIUM";
            }
            return "LOW";
        }
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

    private boolean isCoreMajorAsset(String symbol) {
        String normalized = symbol == null
                ? ""
                : symbol.trim().toUpperCase(Locale.US).replaceAll("[^A-Z0-9]", "");
        return CORE_MAJOR_SYMBOLS.contains(normalized);
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

    private BigDecimal exposureUsd(DefiPosition position) {
        if (position.grossSupplyUsd() != null && position.grossSupplyUsd().signum() != 0) {
            return position.grossSupplyUsd();
        }
        if (position.netUsd() == null) {
            return null;
        }
        return position.netUsd().abs();
    }

    private record HistoryValue(LocalDate date, double valueUsd) {
    }

    private record DrawdownPoint(
            double maxDrawdownPct,
            String peakDate,
            String troughDate,
            double peakUsd,
            double troughUsd) {
    }

    private record TimeSeriesMetrics(
            Double sharpe30d,
            Double sortino30d,
            Double maxDrawdownPct30d,
            Double averageDailyReturnPct30d,
            Double dailyVolatilityPct30d,
            Double downsideDeviationPct30d,
            String historyStartDate,
            String historyEndDate,
            String maxDrawdownPeakDate,
            String maxDrawdownTroughDate,
            Double maxDrawdownPeakUsd,
            Double maxDrawdownTroughUsd,
            int historyDaysAvailable) {

        static TimeSeriesMetrics empty(List<HistoryValue> values) {
            return new TimeSeriesMetrics(
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    values.isEmpty() ? null : values.get(0).date().toString(),
                    values.isEmpty() ? null : values.get(values.size() - 1).date().toString(),
                    null,
                    null,
                    null,
                    null,
                    values.size());
        }
    }
}
