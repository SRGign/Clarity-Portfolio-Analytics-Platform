package com.pnltracker.hyperliquid;

import java.math.BigDecimal;

public record HyperliquidPortfolioWindow(
        String period,
        BigDecimal latestAccountValue,
        BigDecimal latestPnl,
        int accountValueSamples,
        int pnlSamples) {
}
