package com.pnltracker.provider;

import java.math.BigDecimal;

public record ProviderAsset(
        String walletAddress,
        String network,
        String tokenAddress,
        String symbol,
        String name,
        int decimals,
        BigDecimal quantity,
        BigDecimal priceUsd,
        boolean nativeToken,
        String logoUrl) {
}
