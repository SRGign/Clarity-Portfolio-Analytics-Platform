package com.pnltracker.domain;

import java.math.BigDecimal;

public record LendingPosition(
        String positionId,
        String walletAddress,
        String network,
        String protocolKey,
        String protocolName,
        LendingPositionSide positionSide,
        LendingDetectionMode detectionMode,
        LendingCoverage coverage,
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
