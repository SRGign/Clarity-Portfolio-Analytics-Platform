package com.pnltracker.defi.service;

import java.util.List;

import com.pnltracker.defi.model.DefiPosition;
import com.pnltracker.defi.model.DefiPositionSummary;
import com.pnltracker.domain.LendingPosition;
import com.pnltracker.domain.LendingPositionSummary;

public record DefiDetectionResult(
        List<DefiPosition> defiPositions,
        DefiPositionSummary defiSummary,
        List<LendingPosition> lendingPositions,
        LendingPositionSummary lendingSummary) {
}
