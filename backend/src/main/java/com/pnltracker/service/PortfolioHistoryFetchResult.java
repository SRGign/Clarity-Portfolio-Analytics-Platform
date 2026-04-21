package com.pnltracker.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

public record PortfolioHistoryFetchResult(
        String source,
        Map<LocalDate, BigDecimal> totalsByDate,
        Set<String> missingChains) {
}
