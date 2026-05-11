package com.pnltracker.ai;

import com.pnltracker.domain.AssetBalance;
import com.pnltracker.domain.ChainAllocation;
import com.pnltracker.analytics.PortfolioMetrics;
import com.pnltracker.market.StableYieldMarket;

import java.util.List;

public record PortfolioContext(
        double totalValueUsd,
        int walletCount,
        List<ChainAllocation> chainAllocations,
        List<TokenExposure> tokenExposures,
        List<AssetBalance> topAssets,
        List<AiDefiPosition> defiPositions,
        double totalDefiValueUsd,
        double stableUsd,
        double deployedStableUsd,
        double stableAllocationPct,
        double defiAsPctOfPortfolio,
        String largestPositionSymbol,
        double largestPositionPct,
        double idleStableUsd,
        PortfolioMetrics riskMetrics,
        StableYieldMarket stableYieldMarket) {
}
