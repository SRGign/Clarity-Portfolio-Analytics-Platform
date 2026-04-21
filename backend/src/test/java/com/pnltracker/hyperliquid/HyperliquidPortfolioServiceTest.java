package com.pnltracker.hyperliquid;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.pnltracker.domain.AssetBalance;
import com.pnltracker.domain.PortfolioAnalysis;

class HyperliquidPortfolioServiceTest {

    @Test
    void aggregatesHyperliquidIntoSingleNetworkAnalysis() {
        HyperliquidPortfolioService service = new HyperliquidPortfolioService(new StubHyperliquidService(Map.of(
                "0x1", report(summary(
                        "0x1",
                        "120",
                        "15",
                        List.of(
                                new HyperliquidSpotBalance("USDC", 0, new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE, new BigDecimal("100.00")),
                                new HyperliquidSpotBalance("UETH", 1, new BigDecimal("0.002"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("2000"), new BigDecimal("4.00")),
                                new HyperliquidSpotBalance("DUST", 2, new BigDecimal("5"), BigDecimal.ZERO, BigDecimal.ZERO, null, null)),
                        List.of(new HyperliquidVaultEquity("0xvault1", new BigDecimal("1.50"))))),
                "0x2", report(summary(
                        "0x2",
                        "80",
                        "0",
                        List.of(
                                new HyperliquidSpotBalance("USDC", 0, new BigDecimal("50"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE, new BigDecimal("50.00"))),
                        List.of())))));

        PortfolioAnalysis analysis = service.getAnalysis(List.of("0x1", "0x2"));

        assertThat(analysis.summary().totalUsd()).isEqualByComparingTo("200.00");
        assertThat(analysis.summary().allocations()).hasSize(1);
        assertThat(analysis.summary().allocations().get(0).network()).isEqualTo("hyperliquid");
        assertThat(analysis.summary().hiddenAssets()).isEqualTo(1);

        List<AssetBalance> assets = analysis.assets();
        assertThat(assets).extracting(AssetBalance::symbol)
                .contains("USDC", "UETH", "PERP", "VAULT", "HL-CASH");
        assertThat(assets.stream()
                .filter(asset -> "USDC".equals(asset.symbol()))
                .findFirst()
                .orElseThrow()
                .valueUsd()).isEqualByComparingTo("150.00");
    }

    private static HyperliquidReport report(HyperliquidSummary summary) {
        return new HyperliquidReport(summary, List.of());
    }

    private static HyperliquidSummary summary(
            String user,
            String latestPortfolioValue,
            String perpAccountValue,
            List<HyperliquidSpotBalance> spotBalances,
            List<HyperliquidVaultEquity> vaults) {
        return new HyperliquidSummary(
                user,
                "user",
                new BigDecimal(latestPortfolioValue),
                new BigDecimal(perpAccountValue),
                spotBalances.stream()
                        .map(HyperliquidSpotBalance::valueUsd)
                        .filter(value -> value != null)
                        .reduce(BigDecimal.ZERO, BigDecimal::add),
                vaults.stream().map(HyperliquidVaultEquity::equity).reduce(BigDecimal.ZERO, BigDecimal::add),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                List.of(),
                List.of(),
                spotBalances,
                List.of(),
                vaults);
    }

    private static final class StubHyperliquidService extends HyperliquidService {
        private final Map<String, HyperliquidReport> reports;

        private StubHyperliquidService(Map<String, HyperliquidReport> reports) {
            super(null);
            this.reports = reports;
        }

        @Override
        public HyperliquidReport fetchReport(String user) {
            return reports.get(user);
        }
    }
}
