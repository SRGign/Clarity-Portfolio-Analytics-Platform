package com.pnltracker.hyperliquid;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record HyperliquidHistoryPoint(
        Instant timestamp,
        LocalDate localDate,
        BigDecimal accountValueUsd,
        String period) {
}
