package com.pnltracker.hyperliquid;

import java.math.BigDecimal;

public record HyperliquidPerpPosition(
        String coin,
        BigDecimal size,
        BigDecimal positionValue,
        BigDecimal entryPx,
        BigDecimal unrealizedPnl,
        BigDecimal returnOnEquity,
        BigDecimal liquidationPx,
        BigDecimal leverage) {
}
