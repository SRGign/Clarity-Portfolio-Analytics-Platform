package com.pnltracker.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pnltracker.defi.model.DefiPositionSummary;
import com.pnltracker.domain.AssetBalance;
import com.pnltracker.domain.LendingPositionSummary;
import com.pnltracker.domain.PortfolioAnalysis;
import com.pnltracker.domain.PortfolioSummary;
import com.pnltracker.service.PortfolioHistoryPeriod;
import com.pnltracker.service.PortfolioHistoryResult;
import com.pnltracker.service.PortfolioHistoryService;
import com.pnltracker.service.PortfolioOverviewService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

class PortfolioMetricsCalculatorTest {

    @Test
    void treatsEthAndBtcAboveThirtyPercentAsCoreExposure() {
        assertCoreMajorRisk("ETH");
        assertCoreMajorRisk("BTC");
        assertCoreMajorRisk("WETH");
        assertCoreMajorRisk("WBTC");
    }

    @Test
    void treatsNonCoreAssetAboveThirtyPercentAsHighConcentration() {
        PortfolioMetrics metrics = calculateMetricsForLargestAsset("SOL", 45.0d);

        assertThat(metrics.concentrationPct()).isEqualTo(45.0d);
        assertThat(metrics.concentrationRisk()).isEqualTo("HIGH");
    }

    private static void assertCoreMajorRisk(String symbol) {
        PortfolioMetrics metrics = calculateMetricsForLargestAsset(symbol, 45.0d);

        assertThat(metrics.concentrationPct()).isEqualTo(45.0d);
        assertThat(metrics.concentrationRisk()).isEqualTo("LOW");
    }

    private static PortfolioMetrics calculateMetricsForLargestAsset(String symbol, double pct) {
        List<String> addresses = List.of("0xabc");
        List<String> chains = List.of("ethereum");
        PortfolioOverviewService overviewService = mock(PortfolioOverviewService.class);
        PortfolioHistoryService historyService = mock(PortfolioHistoryService.class);
        PortfolioMetricsCalculator calculator = new PortfolioMetricsCalculator(
                overviewService,
                historyService,
                Clock.fixed(Instant.parse("2026-05-10T00:00:00Z"), ZoneOffset.UTC));

        when(overviewService.getOverview(addresses, chains)).thenReturn(portfolio(symbol, pct));
        when(historyService.getHistory(any(), any(), any(PortfolioHistoryPeriod.class)))
                .thenReturn(new PortfolioHistoryResult(
                        PortfolioHistoryPeriod.D30,
                        "test",
                        List.of(),
                        false,
                        List.of(),
                        Instant.parse("2026-05-10T00:00:00Z")));

        return calculator.calculate(addresses, chains);
    }

    private static PortfolioAnalysis portfolio(String largestSymbol, double largestPct) {
        double total = 10_000.0d;
        double largestUsd = total * largestPct / 100.0d;
        double stableUsd = 2_000.0d;
        double remainderUsd = total - largestUsd - stableUsd;

        return new PortfolioAnalysis(
                List.of(
                        asset(largestSymbol, largestUsd),
                        asset("USDC", stableUsd),
                        asset("LINK", remainderUsd)),
                new PortfolioSummary(
                        BigDecimal.valueOf(total),
                        3,
                        0,
                        List.of(),
                        List.of()),
                List.of(),
                LendingPositionSummary.empty(),
                List.of(),
                DefiPositionSummary.empty());
    }

    private static AssetBalance asset(String symbol, double valueUsd) {
        return new AssetBalance(
                "ethereum:" + symbol.toLowerCase(),
                "ethereum",
                null,
                symbol,
                symbol,
                18,
                BigDecimal.ONE,
                BigDecimal.valueOf(valueUsd),
                BigDecimal.valueOf(valueUsd),
                true,
                null);
    }
}
