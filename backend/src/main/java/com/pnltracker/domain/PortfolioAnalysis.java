package com.pnltracker.domain;

import java.util.List;

import com.pnltracker.defi.model.DefiPosition;
import com.pnltracker.defi.model.DefiPositionSummary;

public record PortfolioAnalysis(
        List<AssetBalance> assets,
        PortfolioSummary summary,
        List<LendingPosition> lendingPositions,
        LendingPositionSummary lendingSummary,
        List<DefiPosition> defiPositions,
        DefiPositionSummary defiSummary) {

    public PortfolioAnalysis(
            List<AssetBalance> assets,
            PortfolioSummary summary,
            List<LendingPosition> lendingPositions,
            LendingPositionSummary lendingSummary) {
        this(
                assets,
                summary,
                lendingPositions,
                lendingSummary,
                List.of(),
                DefiPositionSummary.empty());
    }
}
