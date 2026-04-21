package com.pnltracker.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record PortfolioHistorySnapshot(
        String scopeHash,
        List<String> addresses,
        List<String> chains,
        LocalDate localDate,
        BigDecimal totalUsd,
        String source,
        boolean partial,
        List<String> missingChains,
        Instant createdAt) {
}
