package com.pnltracker.hyperliquid;

import java.math.BigDecimal;

public record HyperliquidSpotBalance(
        String coin,
        int token,
        BigDecimal total,
        BigDecimal hold,
        BigDecimal entryNtl,
        BigDecimal priceUsd,
        BigDecimal valueUsd) {
}
