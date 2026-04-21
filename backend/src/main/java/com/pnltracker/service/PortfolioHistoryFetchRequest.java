package com.pnltracker.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import com.pnltracker.domain.ChainDefinition;

public record PortfolioHistoryFetchRequest(
        List<String> addresses,
        List<ChainDefinition> chains,
        Set<LocalDate> requiredDates,
        PortfolioHistoryPeriod period) {
}
