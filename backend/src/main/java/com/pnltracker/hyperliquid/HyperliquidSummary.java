package com.pnltracker.hyperliquid;

import java.math.BigDecimal;
import java.util.List;

public record HyperliquidSummary(
        String user,
        String role,
        BigDecimal latestPortfolioAccountValue,
        BigDecimal perpAccountValue,
        BigDecimal totalSpotValue,
        BigDecimal totalVaultEquity,
        BigDecimal withdrawable,
        BigDecimal totalPerpNotional,
        BigDecimal totalMarginUsed,
        List<HyperliquidPortfolioWindow> portfolioWindows,
        List<HyperliquidHistoryPoint> accountValueHistory,
        List<HyperliquidSpotBalance> spotBalances,
        List<HyperliquidPerpPosition> perpPositions,
        List<HyperliquidVaultEquity> vaultEquities) {
}
