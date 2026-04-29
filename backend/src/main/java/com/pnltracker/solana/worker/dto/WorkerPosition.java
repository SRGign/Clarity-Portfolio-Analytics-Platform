package com.pnltracker.solana.worker.dto;

import java.util.List;
import java.util.Map;

public record WorkerPosition(
        String protocolId,
        String protocolName,
        String category,
        String positionType,
        String positionId,
        List<WorkerPositionToken> tokens,
        double totalValueUsd,
        List<WorkerPositionToken> pendingRewards,
        Map<String, Object> metadata) {
}
