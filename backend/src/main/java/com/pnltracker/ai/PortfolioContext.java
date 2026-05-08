package com.pnltracker.ai;

import com.pnltracker.domain.AssetBalance;
import com.pnltracker.domain.ChainAllocation;
import com.pnltracker.market.StableYieldMarket;
import com.pnltracker.zerion.ZerionPosition;

import java.util.List;

public record PortfolioContext(
        double totalValueUsd,
        int walletCount,
        List<ChainAllocation> chainAllocations,
        List<AssetBalance> topAssets,
        List<ZerionPosition> evmDefiPositions,
        double totalDefiValueUsd,
        double stableAllocationPct,
        double defiAsPctOfPortfolio,
        String largestPositionSymbol,
        double largestPositionPct,
        double idleStableUsd,
        StableYieldMarket stableYieldMarket) {
}
