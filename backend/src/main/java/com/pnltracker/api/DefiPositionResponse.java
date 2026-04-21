package com.pnltracker.api;

import java.math.BigDecimal;

public record DefiPositionResponse(
        String positionId,
        String walletAddress,
        String network,
        String protocolKey,
        String protocolName,
        String positionType,
        String positionSide,
        String detectionMode,
        String coverage,
        String sourceAssetId,
        String underlyingTokenAddress,
        String underlyingSymbol,
        String underlyingName,
        BigDecimal quantity,
        BigDecimal priceUsd,
        BigDecimal grossSupplyUsd,
        BigDecimal grossDebtUsd,
        BigDecimal netUsd,
        boolean alreadyCountedInPortfolio) {
}
