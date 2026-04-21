package com.pnltracker.api;

import java.util.List;

public record PortfolioOverviewResponse(
        PortfolioSummaryResponse summary,
        List<AssetRowResponse> assets,
        List<LendingPositionResponse> positions,
        LendingPositionSummaryResponse positionSummary,
        List<DefiPositionResponse> defiPositions,
        DefiPositionSummaryResponse defiSummary) {

    public PortfolioOverviewResponse(
            PortfolioSummaryResponse summary,
            List<AssetRowResponse> assets,
            List<LendingPositionResponse> positions,
            LendingPositionSummaryResponse positionSummary) {
        this(
                summary,
                assets,
                positions,
                positionSummary,
                List.of(),
                new DefiPositionSummaryResponse(0, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, false, List.of()));
    }
}
