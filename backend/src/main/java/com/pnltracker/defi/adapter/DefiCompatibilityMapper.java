package com.pnltracker.defi.adapter;

import java.util.List;

import com.pnltracker.defi.model.DefiCoverage;
import com.pnltracker.defi.model.DefiDetectionMode;
import com.pnltracker.defi.model.DefiExposureLeg;
import com.pnltracker.defi.model.DefiPosition;
import com.pnltracker.defi.model.DefiPositionSide;
import com.pnltracker.defi.model.DefiPositionType;
import com.pnltracker.domain.LendingCoverage;
import com.pnltracker.domain.LendingDetectionMode;
import com.pnltracker.domain.LendingPosition;
import com.pnltracker.domain.LendingPositionSide;

public final class DefiCompatibilityMapper {

    private DefiCompatibilityMapper() {
    }

    public static DefiPosition fromLendingPosition(LendingPosition position) {
        return new DefiPosition(
                position.positionId(),
                position.walletAddress(),
                position.network(),
                position.protocolKey(),
                position.protocolName(),
                DefiPositionType.LENDING,
                fromLendingSide(position.positionSide()),
                fromLendingMode(position.detectionMode()),
                fromLendingCoverage(position.coverage()),
                position.sourceAssetId(),
                position.underlyingTokenAddress(),
                position.underlyingSymbol(),
                position.underlyingName(),
                position.quantity(),
                position.priceUsd(),
                position.supplyUsd(),
                position.debtUsd(),
                position.netUsd() == null
                        ? defaultNetUsd(position.supplyUsd(), position.debtUsd())
                        : position.netUsd(),
                position.alreadyCountedInPortfolio(),
                List.of(new DefiExposureLeg(
                        position.sourceAssetId(),
                        position.underlyingTokenAddress(),
                        position.underlyingSymbol(),
                        position.underlyingName(),
                        position.quantity(),
                        position.priceUsd(),
                        position.supplyUsd(),
                        position.alreadyCountedInPortfolio())));
    }

    public static LendingPosition toLendingPosition(DefiPosition position) {
        return new LendingPosition(
                position.positionId(),
                position.walletAddress(),
                position.network(),
                position.protocolKey(),
                position.protocolName(),
                toLendingSide(position.positionSide()),
                toLendingMode(position.detectionMode()),
                toLendingCoverage(position.coverage()),
                position.sourceAssetId(),
                position.underlyingTokenAddress(),
                position.underlyingSymbol(),
                position.underlyingName(),
                position.quantity(),
                position.priceUsd(),
                position.grossSupplyUsd(),
                position.grossDebtUsd(),
                position.netUsd(),
                position.alreadyCountedInPortfolio());
    }

    private static DefiPositionSide fromLendingSide(LendingPositionSide side) {
        if (side == null) {
            return DefiPositionSide.NET;
        }
        return switch (side) {
            case SUPPLY -> DefiPositionSide.SUPPLY;
            case DEBT -> DefiPositionSide.DEBT;
            case NET -> DefiPositionSide.NET;
        };
    }

    private static LendingPositionSide toLendingSide(DefiPositionSide side) {
        if (side == null || side == DefiPositionSide.NET) {
            return LendingPositionSide.NET;
        }
        return switch (side) {
            case SUPPLY -> LendingPositionSide.SUPPLY;
            case DEBT -> LendingPositionSide.DEBT;
            case NET -> LendingPositionSide.NET;
        };
    }

    private static DefiDetectionMode fromLendingMode(LendingDetectionMode mode) {
        if (mode == null) {
            return DefiDetectionMode.HEURISTIC;
        }
        return switch (mode) {
            case HEURISTIC -> DefiDetectionMode.HEURISTIC;
            case ADAPTER -> DefiDetectionMode.PROTOCOL;
        };
    }

    private static LendingDetectionMode toLendingMode(DefiDetectionMode mode) {
        return mode == DefiDetectionMode.PROTOCOL ? LendingDetectionMode.ADAPTER : LendingDetectionMode.HEURISTIC;
    }

    private static DefiCoverage fromLendingCoverage(LendingCoverage coverage) {
        if (coverage == null) {
            return DefiCoverage.PARTIAL;
        }
        return switch (coverage) {
            case COMPLETE -> DefiCoverage.FULL;
            case PARTIAL -> DefiCoverage.PARTIAL;
        };
    }

    private static LendingCoverage toLendingCoverage(DefiCoverage coverage) {
        return coverage == DefiCoverage.FULL ? LendingCoverage.COMPLETE : LendingCoverage.PARTIAL;
    }

    private static java.math.BigDecimal defaultNetUsd(java.math.BigDecimal supplyUsd, java.math.BigDecimal debtUsd) {
        java.math.BigDecimal safeSupply = supplyUsd == null ? java.math.BigDecimal.ZERO : supplyUsd;
        java.math.BigDecimal safeDebt = debtUsd == null ? java.math.BigDecimal.ZERO : debtUsd;
        return safeSupply.subtract(safeDebt);
    }
}
