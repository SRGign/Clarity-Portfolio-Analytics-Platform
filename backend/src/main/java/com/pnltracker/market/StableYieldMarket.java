package com.pnltracker.market;

import java.time.Instant;
import java.util.List;

public record StableYieldMarket(
        List<StableYieldOpportunity> opportunities,
        Double benchmarkApy,
        Instant asOf,
        String source,
        boolean stale) {

    public static StableYieldMarket empty(String source) {
        return new StableYieldMarket(List.of(), null, Instant.now(), source, false);
    }

    public boolean hasOpportunities() {
        return opportunities != null && !opportunities.isEmpty();
    }
}
