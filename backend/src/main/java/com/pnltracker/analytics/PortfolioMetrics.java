package com.pnltracker.analytics;

public record PortfolioMetrics(
        double concentrationPct,
        String concentrationAsset,
        String concentrationRisk,
        double stableAllocationPct,
        double defiAllocationPct,
        double idleStableUsd,
        double monthlyOpportunityCostUsd,
        Double sharpe30d,
        Double sortino30d,
        Double maxDrawdownPct30d,
        int historyDaysAvailable) {
}
