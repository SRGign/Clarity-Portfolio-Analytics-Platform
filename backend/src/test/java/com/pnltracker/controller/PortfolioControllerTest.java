package com.pnltracker.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.pnltracker.api.PortfolioOverviewResponse;
import com.pnltracker.api.PortfolioPositionsResponse;
import com.pnltracker.api.PortfolioRequest;
import com.pnltracker.defi.model.DefiCoverage;
import com.pnltracker.defi.model.DefiDetectionMode;
import com.pnltracker.defi.model.DefiPosition;
import com.pnltracker.defi.model.DefiPositionSide;
import com.pnltracker.defi.model.DefiPositionSummary;
import com.pnltracker.defi.model.DefiPositionType;
import com.pnltracker.domain.AssetBalance;
import com.pnltracker.domain.ChainAllocation;
import com.pnltracker.domain.LendingCoverage;
import com.pnltracker.domain.LendingDetectionMode;
import com.pnltracker.domain.LendingPosition;
import com.pnltracker.domain.LendingPositionSide;
import com.pnltracker.domain.LendingPositionSummary;
import com.pnltracker.domain.PortfolioAnalysis;
import com.pnltracker.domain.PortfolioSummary;
import com.pnltracker.domain.WalletAllocation;
import com.pnltracker.service.ChainCatalogService;
import com.pnltracker.service.PortfolioHistoryService;
import com.pnltracker.service.PortfolioOverviewService;

class PortfolioControllerTest {

    @Test
    void overviewIncludesLendingPositionsAndSummary() {
        PortfolioOverviewService overviewService = mock(PortfolioOverviewService.class);
        when(overviewService.getOverview(List.of("0xabc"), List.of("base"))).thenReturn(sampleAnalysis());

        PortfolioController controller = new PortfolioController(
                overviewService,
                mock(ChainCatalogService.class),
                mock(PortfolioHistoryService.class));

        PortfolioOverviewResponse response = controller.overview(new PortfolioRequest(List.of("0xabc"), List.of("base")));

        assertThat(response.summary().totalUsd()).isEqualByComparingTo("100.00");
        assertThat(response.summary().grossAssetUsd()).isEqualByComparingTo("100.00");
        assertThat(response.summary().grossLiabilityUsd()).isEqualByComparingTo("0.00");
        assertThat(response.summary().netUsd()).isEqualByComparingTo("100.00");
        assertThat(response.assets()).hasSize(1);
        assertThat(response.positions()).hasSize(1);
        assertThat(response.positionSummary().trackedPositions()).isEqualTo(1);
        assertThat(response.positionSummary().partialCoverage()).isTrue();
        assertThat(response.positionSummary().detectionModes()).containsExactly("heuristic");
        assertThat(response.positions().get(0).coverage()).isEqualTo("partial");
        assertThat(response.defiPositions()).hasSize(1);
        assertThat(response.defiSummary().trackedPositions()).isEqualTo(1);
    }

    @Test
    void positionsEndpointReturnsOnlyLendingPayload() {
        PortfolioOverviewService overviewService = mock(PortfolioOverviewService.class);
        when(overviewService.getOverview(List.of("0xabc"), List.of("base"))).thenReturn(sampleAnalysis());

        PortfolioController controller = new PortfolioController(
                overviewService,
                mock(ChainCatalogService.class),
                mock(PortfolioHistoryService.class));

        PortfolioPositionsResponse response = controller.positions(new PortfolioRequest(List.of("0xabc"), List.of("base")));

        assertThat(response.positions()).hasSize(1);
        assertThat(response.positionSummary().visibleSupplyUsd()).isEqualByComparingTo("100.00");
        assertThat(response.positionSummary().visibleDebtUsd()).isEqualByComparingTo("0.00");
        assertThat(response.positionSummary().visibleNetUsd()).isEqualByComparingTo("100.00");
        assertThat(response.positions().get(0).alreadyCountedInPortfolio()).isTrue();
        assertThat(response.positions().get(0).detectionMode()).isEqualTo("heuristic");
        assertThat(response.defiPositions()).hasSize(1);
        assertThat(response.defiSummary().visibleSupplyUsd()).isEqualByComparingTo("100.00");
    }

    @Test
    void overviewAndPositionsExposeMixedSupplyAndDebtSemantics() {
        PortfolioOverviewService overviewService = mock(PortfolioOverviewService.class);
        when(overviewService.getOverview(List.of("0xabc"), List.of("base"))).thenReturn(sampleSupplyAndDebtAnalysis());

        PortfolioController controller = new PortfolioController(
                overviewService,
                mock(ChainCatalogService.class),
                mock(PortfolioHistoryService.class));

        PortfolioOverviewResponse overview = controller.overview(new PortfolioRequest(List.of("0xabc"), List.of("base")));
        PortfolioPositionsResponse positions = controller.positions(new PortfolioRequest(List.of("0xabc"), List.of("base")));

        assertThat(overview.summary().grossAssetUsd()).isEqualByComparingTo("100.00");
        assertThat(overview.summary().grossLiabilityUsd()).isEqualByComparingTo("40.00");
        assertThat(overview.summary().netUsd()).isEqualByComparingTo("60.00");
        assertThat(overview.summary().totalUsd()).isEqualByComparingTo("60.00");

        assertThat(positions.positions()).hasSize(2);
        assertThat(positions.positions())
                .extracting(com.pnltracker.api.LendingPositionResponse::positionSide)
                .containsExactlyInAnyOrder("supply", "debt");
        assertThat(positions.positionSummary().visibleSupplyUsd()).isEqualByComparingTo("100.00");
        assertThat(positions.positionSummary().visibleDebtUsd()).isEqualByComparingTo("40.00");
        assertThat(positions.positionSummary().visibleNetUsd()).isEqualByComparingTo("60.00");
        assertThat(overview.defiPositions()).hasSize(2);
        assertThat(overview.defiSummary().visibleSupplyUsd()).isEqualByComparingTo("100.00");
        assertThat(overview.defiSummary().visibleDebtUsd()).isEqualByComparingTo("40.00");
        assertThat(overview.defiSummary().visibleNetUsd()).isEqualByComparingTo("60.00");
        assertThat(positions.defiPositions()).hasSize(2);
    }

    private static PortfolioAnalysis sampleAnalysis() {
        AssetBalance asset = new AssetBalance(
                "base-mainnet:0xwrapper",
                "base-mainnet",
                "0xwrapper",
                "aUSDC",
                "Aave USDC",
                6,
                new BigDecimal("100"),
                BigDecimal.ONE,
                new BigDecimal("100.00"),
                false,
                null);
        LendingPosition position = new LendingPosition(
                "base-mainnet:0xabc:aave:0xwrapper:supply",
                "0xabc",
                "base-mainnet",
                "aave",
                "Aave",
                LendingPositionSide.SUPPLY,
                LendingDetectionMode.HEURISTIC,
                LendingCoverage.PARTIAL,
                "base-mainnet:0xwrapper",
                "0xusdc",
                "USDC",
                "USD Coin",
                new BigDecimal("100"),
                BigDecimal.ONE,
                new BigDecimal("100.00"),
                null,
                null,
                true);
        PortfolioSummary summary = new PortfolioSummary(
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
                BigDecimal.ZERO.setScale(2),
                new BigDecimal("100.00"),
                1,
                0,
                List.of(new ChainAllocation("base-mainnet", "Base", new BigDecimal("100.00"))),
                List.of(new WalletAllocation("0xabc", new BigDecimal("100.00"))));
        return new PortfolioAnalysis(
                List.of(asset),
                summary,
                List.of(position),
                LendingPositionSummary.fromPositions(List.of(position)),
                List.of(defiPositionFromLending(position)),
                DefiPositionSummary.fromPositions(List.of(defiPositionFromLending(position))));
    }

    private static PortfolioAnalysis sampleSupplyAndDebtAnalysis() {
        AssetBalance supplyAsset = new AssetBalance(
                "base-mainnet:0xausdc",
                "base-mainnet",
                "0xausdc",
                "aUSDC",
                "Aave USDC",
                6,
                new BigDecimal("100"),
                BigDecimal.ONE,
                new BigDecimal("100.00"),
                false,
                null);
        AssetBalance debtAsset = new AssetBalance(
                "base-mainnet:0xdusdc",
                "base-mainnet",
                "0xdusdc",
                "variableDebtUSDC",
                "Variable Debt USDC",
                6,
                new BigDecimal("40"),
                BigDecimal.ONE,
                new BigDecimal("40.00"),
                false,
                null);

        LendingPosition supply = new LendingPosition(
                "base-mainnet:0xabc:aave:0xausdc:supply",
                "0xabc",
                "base-mainnet",
                "aave",
                "Aave",
                LendingPositionSide.SUPPLY,
                LendingDetectionMode.HEURISTIC,
                LendingCoverage.PARTIAL,
                "base-mainnet:0xausdc",
                "0xusdc",
                "USDC",
                "USD Coin",
                new BigDecimal("100"),
                BigDecimal.ONE,
                new BigDecimal("100.00"),
                BigDecimal.ZERO.setScale(2),
                new BigDecimal("100.00"),
                true);
        LendingPosition debt = new LendingPosition(
                "base-mainnet:0xabc:aave:0xdusdc:debt",
                "0xabc",
                "base-mainnet",
                "aave",
                "Aave",
                LendingPositionSide.DEBT,
                LendingDetectionMode.HEURISTIC,
                LendingCoverage.PARTIAL,
                "base-mainnet:0xdusdc",
                "0xusdc",
                "USDC",
                "USD Coin",
                new BigDecimal("40"),
                BigDecimal.ONE,
                BigDecimal.ZERO.setScale(2),
                new BigDecimal("40.00"),
                new BigDecimal("-40.00"),
                false);

        PortfolioSummary summary = new PortfolioSummary(
                new BigDecimal("60.00"),
                new BigDecimal("100.00"),
                new BigDecimal("40.00"),
                new BigDecimal("60.00"),
                2,
                0,
                List.of(new ChainAllocation("base-mainnet", "Base", new BigDecimal("60.00"))),
                List.of(new WalletAllocation("0xabc", new BigDecimal("60.00"))));

        List<LendingPosition> positions = List.of(supply, debt);
        return new PortfolioAnalysis(
                List.of(supplyAsset, debtAsset),
                summary,
                positions,
                LendingPositionSummary.fromPositions(positions),
                List.of(defiPositionFromLending(supply), defiPositionFromLending(debt)),
                DefiPositionSummary.fromPositions(List.of(defiPositionFromLending(supply), defiPositionFromLending(debt))));
    }

    private static DefiPosition defiPositionFromLending(LendingPosition position) {
        return new DefiPosition(
                position.positionId(),
                position.walletAddress(),
                position.network(),
                position.protocolKey(),
                position.protocolName(),
                DefiPositionType.LENDING,
                switch (position.positionSide()) {
                    case SUPPLY -> DefiPositionSide.SUPPLY;
                    case DEBT -> DefiPositionSide.DEBT;
                    case NET -> DefiPositionSide.NET;
                },
                position.detectionMode() == LendingDetectionMode.ADAPTER ? DefiDetectionMode.PROTOCOL : DefiDetectionMode.HEURISTIC,
                position.coverage() == LendingCoverage.COMPLETE ? DefiCoverage.FULL : DefiCoverage.PARTIAL,
                position.sourceAssetId(),
                position.underlyingTokenAddress(),
                position.underlyingSymbol(),
                position.underlyingName(),
                position.quantity(),
                position.priceUsd(),
                position.supplyUsd(),
                position.debtUsd(),
                position.netUsd(),
                position.alreadyCountedInPortfolio(),
                List.of());
    }
}
