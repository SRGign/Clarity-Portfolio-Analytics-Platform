package com.pnltracker.api;

import java.util.List;

public record PortfolioHistoryResponse(
        String period,
        String scopeHash,
        List<PortfolioHistoryPointResponse> points,
        boolean partial,
        List<String> missingChains,
        String asOf) {
}
