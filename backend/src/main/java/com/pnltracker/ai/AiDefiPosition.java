package com.pnltracker.ai;

public record AiDefiPosition(
        String chain,
        String protocolName,
        String protocolModule,
        String positionType,
        String tokenSymbol,
        double valueUsd,
        double supplyUsd,
        double debtUsd,
        boolean alreadyCountedInSpotTotals) {
}
