package com.pnltracker.api;

import java.math.BigDecimal;

public record PortfolioHistoryPointResponse(
        String localDate,
        BigDecimal totalUsd,
        String source,
        boolean persisted) {
}
