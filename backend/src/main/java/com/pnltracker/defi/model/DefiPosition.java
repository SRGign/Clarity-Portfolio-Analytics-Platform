package com.pnltracker.defi.model;

import java.math.BigDecimal;
import java.util.List;

public record DefiPosition(
        String positionId,
        String walletAddress,
        String network,
        String protocolKey,
        String protocolName,
        DefiPositionType positionType,
        DefiPositionSide positionSide,
        DefiDetectionMode detectionMode,
        DefiCoverage coverage,
        String sourceAssetId,
        String underlyingTokenAddress,
        String underlyingSymbol,
        String underlyingName,
        BigDecimal quantity,
        BigDecimal priceUsd,
        BigDecimal grossSupplyUsd,
        BigDecimal grossDebtUsd,
        BigDecimal netUsd,
        boolean alreadyCountedInPortfolio,
        List<DefiExposureLeg> legs) {
}
