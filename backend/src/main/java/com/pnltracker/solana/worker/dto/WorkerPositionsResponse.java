package com.pnltracker.solana.worker.dto;

import java.util.List;

public record WorkerPositionsResponse(
        String walletAddress,
        String fetchedAt,
        List<WorkerPosition> positions,
        List<WorkerProtocolError> errors) {
}
