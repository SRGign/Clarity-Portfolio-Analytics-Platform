package com.pnltracker.solana.defi.model;

import java.math.BigDecimal;
import java.time.Instant;

public record SolanaTokenizedDefiPosition(
        String walletAddress,
        String mintAddress,
        String symbol,
        String protocolKey,
        String protocolName,
        String positionType,
        BigDecimal quantity,
        BigDecimal priceUsd,
        BigDecimal valueUsd,
        String source,
        String confidence,
        boolean alreadyCountedInSpotTotals,
        Instant observedAt,
        String rawPayloadJson) {
}
