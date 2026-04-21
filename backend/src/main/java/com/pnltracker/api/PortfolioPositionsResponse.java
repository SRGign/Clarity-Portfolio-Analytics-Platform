package com.pnltracker.api;

import java.util.List;

public record PortfolioPositionsResponse(
        List<LendingPositionResponse> positions,
        LendingPositionSummaryResponse positionSummary,
        List<DefiPositionResponse> defiPositions,
        DefiPositionSummaryResponse defiSummary) {

    public PortfolioPositionsResponse(
            List<LendingPositionResponse> positions,
            LendingPositionSummaryResponse positionSummary) {
        this(
                positions,
                positionSummary,
                List.of(),
                new DefiPositionSummaryResponse(0, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, false, List.of()));
    }
}
