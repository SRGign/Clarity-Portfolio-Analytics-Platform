package com.pnltracker.defi.model;

import java.math.BigDecimal;

public record DefiExposureLeg(
        String sourceAssetId,
        String tokenAddress,
        String symbol,
        String name,
        BigDecimal quantity,
        BigDecimal priceUsd,
        BigDecimal valueUsd,
        boolean alreadyCountedInPortfolio) {
}
