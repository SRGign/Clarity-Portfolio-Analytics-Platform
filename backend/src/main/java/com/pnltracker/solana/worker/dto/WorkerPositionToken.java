package com.pnltracker.solana.worker.dto;

public record WorkerPositionToken(
        String mint,
        String symbol,
        int decimals,
        double amount,
        double priceUsd,
        double valueUsd) {
}
