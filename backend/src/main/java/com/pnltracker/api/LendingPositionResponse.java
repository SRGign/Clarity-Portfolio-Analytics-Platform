package com.pnltracker.api;

import java.math.BigDecimal;

public record LendingPositionResponse(
        String positionId,
        String walletAddress,
        String network,
        String protocolKey,
        String protocolName,
        String positionSide,
        String detectionMode,
        String coverage,
        String sourceAssetId,
        String underlyingTokenAddress,
        String underlyingSymbol,
        String underlyingName,
        BigDecimal quantity,
        BigDecimal priceUsd,
        BigDecimal supplyUsd,
        BigDecimal debtUsd,
        BigDecimal netUsd,
        boolean alreadyCountedInPortfolio) {
}
