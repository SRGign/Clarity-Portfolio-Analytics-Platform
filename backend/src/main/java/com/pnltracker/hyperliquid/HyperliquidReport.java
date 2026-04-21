package com.pnltracker.hyperliquid;

import java.util.List;

public record HyperliquidReport(
        HyperliquidSummary summary,
        List<HyperliquidExchange> exchanges) {
}
