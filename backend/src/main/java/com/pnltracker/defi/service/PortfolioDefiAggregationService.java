package com.pnltracker.defi.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import org.springframework.stereotype.Service;

import com.pnltracker.defi.model.DefiPosition;
import com.pnltracker.defi.model.DefiPositionSide;
import com.pnltracker.domain.PortfolioSummary;

@Service
public class PortfolioDefiAggregationService {

    public PortfolioSummary applyDefiAdjustments(PortfolioSummary spotSummary, List<DefiPosition> defiPositions) {
        BigDecimal additionalGrossAssets = defiPositions.stream()
                .filter(position -> !position.alreadyCountedInPortfolio())
                .filter(position -> position.positionSide() != DefiPositionSide.DEBT)
                .map(DefiPosition::grossSupplyUsd)
                .filter(value -> value != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal debtAssetsToRemoveFromSpotTotals = defiPositions.stream()
                .filter(position -> position.positionSide() == DefiPositionSide.DEBT)
                .map(DefiPosition::grossDebtUsd)
                .filter(value -> value != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal grossLiabilityUsd = defiPositions.stream()
                .map(DefiPosition::grossDebtUsd)
                .filter(value -> value != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal grossAssetUsd = spotSummary.totalUsd()
                .subtract(debtAssetsToRemoveFromSpotTotals)
                .add(additionalGrossAssets)
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal netUsd = grossAssetUsd.subtract(grossLiabilityUsd).setScale(2, RoundingMode.HALF_UP);

        return new PortfolioSummary(
                netUsd,
                grossAssetUsd,
                grossLiabilityUsd,
                netUsd,
                spotSummary.trackedAssets(),
                spotSummary.hiddenAssets(),
                spotSummary.allocations(),
                spotSummary.walletAllocations());
    }
}
