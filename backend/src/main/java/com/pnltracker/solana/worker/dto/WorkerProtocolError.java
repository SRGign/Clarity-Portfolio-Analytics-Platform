package com.pnltracker.solana.worker.dto;

public record WorkerProtocolError(
        String protocolId,
        String message) {
}
