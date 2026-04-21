package com.pnltracker.domain;

import java.math.BigDecimal;
import java.util.List;

public record PortfolioSummary(
        BigDecimal totalUsd,
        BigDecimal grossAssetUsd,
        BigDecimal grossLiabilityUsd,
        BigDecimal netUsd,
        int trackedAssets,
        int hiddenAssets,
        List<ChainAllocation> allocations,
        List<WalletAllocation> walletAllocations) {

    public PortfolioSummary(
            BigDecimal totalUsd,
            int trackedAssets,
            int hiddenAssets,
            List<ChainAllocation> allocations,
            List<WalletAllocation> walletAllocations) {
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
