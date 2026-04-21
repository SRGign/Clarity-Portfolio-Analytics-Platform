package com.pnltracker.api;

import java.math.BigDecimal;

public record AssetRowResponse(
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
