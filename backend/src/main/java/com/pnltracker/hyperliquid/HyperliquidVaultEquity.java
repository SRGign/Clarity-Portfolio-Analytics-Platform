package com.pnltracker.hyperliquid;

import java.math.BigDecimal;

public record HyperliquidVaultEquity(
        String vaultAddress,
        BigDecimal equity) {
}
