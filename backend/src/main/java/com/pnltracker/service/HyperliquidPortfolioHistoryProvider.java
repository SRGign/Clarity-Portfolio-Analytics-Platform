package com.pnltracker.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.pnltracker.hyperliquid.HyperliquidHistoryPoint;
import com.pnltracker.hyperliquid.HyperliquidReport;
import com.pnltracker.hyperliquid.HyperliquidService;

@Component
public class HyperliquidPortfolioHistoryProvider implements PortfolioHistoryProvider {

    private static final String SOURCE = "hyperliquid";

    private final HyperliquidService hyperliquidService;

    public HyperliquidPortfolioHistoryProvider(HyperliquidService hyperliquidService) {
        this.hyperliquidService = hyperliquidService;
    }

    @Override
    public PortfolioHistoryFetchResult fetchHistory(PortfolioHistoryFetchRequest request) {
        Map<LocalDate, BigDecimal> totalsByDate = new LinkedHashMap<>();
        for (String address : request.addresses()) {
            try {
                HyperliquidReport report = hyperliquidService.fetchReport(address);
                mergeReportTotals(totalsByDate, report.summary().accountValueHistory(), request.requiredDates());
            } catch (RuntimeException ignored) {
                // Hyperliquid addresses are optional for a given wallet set; skip empty users quietly.
            }
        }
        return new PortfolioHistoryFetchResult(SOURCE, totalsByDate, Set.of());
    }

    private void mergeReportTotals(
            Map<LocalDate, BigDecimal> totalsByDate,
            java.util.List<HyperliquidHistoryPoint> history,
            Set<LocalDate> requiredDates) {
        for (HyperliquidHistoryPoint point : history) {
            if (!requiredDates.contains(point.localDate())) {
                continue;
            }
            totalsByDate.merge(point.localDate(), point.accountValueUsd(), BigDecimal::add);
        }
    }
}
