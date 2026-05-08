package com.pnltracker.market;

public record StableYieldOpportunity(
        String poolId,
        String protocol,
        String protocolName,
        String chain,
        String symbol,
        double apy,
        double apyBase,
        double apyReward,
        double tvlUsd,
        double estimatedMonthlyYieldUsd,
        String riskNote) {
}
