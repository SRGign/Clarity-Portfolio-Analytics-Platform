package com.pnltracker.defi.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.pnltracker.defi.adapter.ProtocolPositionAdapter;
import com.pnltracker.defi.model.DefiCoverage;
import com.pnltracker.defi.model.DefiDetectionMode;
import com.pnltracker.defi.model.DefiPosition;
import com.pnltracker.defi.model.DefiPositionSide;
import com.pnltracker.defi.model.DefiPositionType;
import com.pnltracker.provider.ProviderAsset;

class DefiPositionServiceTest {

    @Test
    void continuesWhenOneAdapterFailsAndKeepsLaterAdapters() {
        ProtocolPositionAdapter failingAdapter = new ProtocolPositionAdapter() {
            @Override
            public String adapterId() {
                return "failing";
            }

            @Override
            public int order() {
                return 10;
            }

            @Override
            public List<DefiPosition> detectPositions(List<String> addresses, List<String> networks, List<ProviderAsset> providerAssets) {
                throw new IllegalStateException("boom");
            }
        };

        ProtocolPositionAdapter succeedingAdapter = new ProtocolPositionAdapter() {
            @Override
            public String adapterId() {
                return "success";
            }

            @Override
            public int order() {
                return 20;
            }

            @Override
            public List<DefiPosition> detectPositions(List<String> addresses, List<String> networks, List<ProviderAsset> providerAssets) {
                return List.of(new DefiPosition(
                        "base-mainnet:0xabc:aave:0xausdc:supply",
                        "0xabc",
                        "base-mainnet",
                        "aave",
                        "Aave",
                        DefiPositionType.LENDING,
                        DefiPositionSide.SUPPLY,
                        DefiDetectionMode.HEURISTIC,
                        DefiCoverage.PARTIAL,
                        "base-mainnet:0xausdc",
                        "0xusdc",
                        "USDC",
                        "USD Coin",
                        new BigDecimal("100"),
                        BigDecimal.ONE,
                        new BigDecimal("100.00"),
                        BigDecimal.ZERO.setScale(2),
                        new BigDecimal("100.00"),
                        true,
                        List.of()));
            }
        };

        DefiPositionService service = new DefiPositionService(List.of(succeedingAdapter, failingAdapter));

        DefiDetectionResult result = service.detect(
                List.of("0xabc"),
                List.of("base-mainnet"),
                List.of(new ProviderAsset(
                        "0xabc",
                        "base-mainnet",
                        "0xausdc",
                        "aUSDC",
                        "Aave USDC",
                        6,
                        new BigDecimal("100"),
                        BigDecimal.ONE,
                        false,
                        null)));

        assertThat(result.defiPositions()).hasSize(1);
        assertThat(result.lendingPositions()).hasSize(1);
        assertThat(result.defiSummary().trackedPositions()).isEqualTo(1);
    }
}
