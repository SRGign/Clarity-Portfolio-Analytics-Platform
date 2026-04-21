package com.pnltracker.api;

import java.math.BigDecimal;
import java.util.List;

public record LendingPositionSummaryResponse(
        int trackedPositions,
        BigDecimal visibleSupplyUsd,
        BigDecimal visibleDebtUsd,
        BigDecimal visibleNetUsd,
        boolean partialCoverage,
        List<String> detectionModes) {

    public LendingPositionSummaryResponse(
            int trackedPositions,
            BigDecimal visibleSupplyUsd,
            boolean partialCoverage,
            List<String> detectionModes) {
        this(
                trackedPositions,
                visibleSupplyUsd,
                BigDecimal.ZERO,
                visibleSupplyUsd,
                partialCoverage,
                detectionModes);
    }
}
