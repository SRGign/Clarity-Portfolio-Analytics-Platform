package com.pnltracker.service;

import java.time.Instant;
import java.util.List;

public record PortfolioHistoryResult(
        PortfolioHistoryPeriod period,
        String scopeHash,
        List<PortfolioHistoryResultPoint> points,
        boolean partial,
        List<String> missingChains,
        Instant asOf) {
}
