package com.pnltracker.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.pnltracker.config.PortfolioProperties;
import com.pnltracker.domain.PortfolioSummary;
import com.pnltracker.provider.AssetPortfolioProvider;
import com.pnltracker.provider.ProviderAsset;
import com.pnltracker.provider.ProviderFetchResult;

class PortfolioAggregationTest {

    private PortfolioService portfolioService;

    @BeforeEach
    void setUp() {
        PortfolioProperties properties = new PortfolioProperties();
        AssetPortfolioProvider provider = new SimpleProvider();
        portfolioService = new PortfolioService(
                provider,
                new ChainCatalogService(),
                new SimpleTtlCache(properties),
                new WrapperHeuristicLendingDetector(properties));
    }

    @Test
    void spotOnlyPortfolioPreservesGrossAndNetTotals() {
        PortfolioSummary summary = portfolioService.getSummary(List.of("0xabc"), List.of("ethereum"));

        assertThat(summary.totalUsd()).isEqualByComparingTo("2000.00");
        assertThat(summary.grossAssetUsd()).isEqualByComparingTo("2000.00");
        assertThat(summary.grossLiabilityUsd()).isEqualByComparingTo("0.00");
        assertThat(summary.netUsd()).isEqualByComparingTo("2000.00");
    }

    private static final class SimpleProvider implements AssetPortfolioProvider {

        @Override
        public ProviderFetchResult fetchAssetsWithDebug(List<String> addresses, List<String> networks) {
            return new ProviderFetchResult(
                    List.of(new ProviderAsset(
                            "0xabc",
                            "eth-mainnet",
                            null,
                            "ETH",
                            "Ethereum",
                            18,
                            BigDecimal.ONE,
                            new BigDecimal("2000"),
                            true,
                            null)),
                    List.of(),
                    List.of());
        }

        @Override
        public String providerName() {
            return "simple";
        }
    }
}
