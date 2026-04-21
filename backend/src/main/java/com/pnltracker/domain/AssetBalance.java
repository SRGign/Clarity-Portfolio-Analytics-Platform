package com.pnltracker.domain;

import java.math.BigDecimal;

public record AssetBalance(
        String assetId,
        String network,
        String tokenAddress,
        String symbol,
        String name,
        int decimals,
        BigDecimal quantity,
        BigDecimal priceUsd,
        BigDecimal valueUsd,
        boolean nativeToken,
        String logoUrl) {
}
