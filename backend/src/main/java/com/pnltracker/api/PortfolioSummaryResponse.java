package com.pnltracker.api;

import java.math.BigDecimal;
import java.util.List;

public record PortfolioSummaryResponse(
        BigDecimal totalUsd,
        BigDecimal grossAssetUsd,
        BigDecimal grossLiabilityUsd,
        BigDecimal netUsd,
        int trackedAssets,
        int hiddenAssets,
        List<ChainAllocationResponse> allocations,
        List<WalletAllocationResponse> walletAllocations) {

    public PortfolioSummaryResponse(
            BigDecimal totalUsd,
            int trackedAssets,
            int hiddenAssets,
            List<ChainAllocationResponse> allocations,
            List<WalletAllocationResponse> walletAllocations) {
        this(
                totalUsd,
                totalUsd,
                BigDecimal.ZERO,
                totalUsd,
                trackedAssets,
                hiddenAssets,
                allocations,
                walletAllocations);
    }
}
