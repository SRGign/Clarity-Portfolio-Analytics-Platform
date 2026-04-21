package com.pnltracker.api;

import java.math.BigDecimal;
import java.util.List;

public record DefiPositionSummaryResponse(
        int trackedPositions,
        BigDecimal visibleSupplyUsd,
        BigDecimal visibleDebtUsd,
        BigDecimal visibleNetUsd,
        boolean partialCoverage,
        List<String> detectionModes) {
}
