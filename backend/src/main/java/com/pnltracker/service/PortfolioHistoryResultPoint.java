package com.pnltracker.service;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PortfolioHistoryResultPoint(
        LocalDate localDate,
        BigDecimal totalUsd,
        String source,
        boolean persisted) {
}
