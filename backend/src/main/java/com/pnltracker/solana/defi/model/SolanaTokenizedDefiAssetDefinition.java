package com.pnltracker.solana.defi.model;

public record SolanaTokenizedDefiAssetDefinition(
        String mintAddress,
        String symbol,
        String protocolKey,
        String protocolName,
        String positionType,
        int decimals,
        String source,
        String confidence,
        boolean enabled,
        boolean alreadyCountedInSpotTotals) {
}
