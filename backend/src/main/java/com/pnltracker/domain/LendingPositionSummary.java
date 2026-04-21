package com.pnltracker.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashSet;
import java.util.List;

public record LendingPositionSummary(
        int trackedPositions,
        BigDecimal visibleSupplyUsd,
        BigDecimal visibleDebtUsd,
        BigDecimal visibleNetUsd,
        boolean partialCoverage,
        List<LendingDetectionMode> detectionModes) {

    public static LendingPositionSummary empty() {
        return new LendingPositionSummary(
                0,
                BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                false,
                List.of());
    }

    public LendingPositionSummary(
            int trackedPositions,
            BigDecimal visibleSupplyUsd,
            boolean partialCoverage,
            List<LendingDetectionMode> detectionModes) {
        this(
                trackedPositions,
                visibleSupplyUsd,
                BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                visibleSupplyUsd,
                partialCoverage,
                detectionModes);
    }

    public static LendingPositionSummary fromPositions(List<LendingPosition> positions) {
        if (positions == null || positions.isEmpty()) {
            return empty();
        }

        BigDecimal visibleSupplyUsd = positions.stream()
                .map(LendingPosition::supplyUsd)
                .filter(value -> value != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal visibleDebtUsd = positions.stream()
                .map(LendingPosition::debtUsd)
                .filter(value -> value != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal visibleNetUsd = positions.stream()
                .map(LendingPosition::netUsd)
                .filter(value -> value != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        boolean partialCoverage = positions.stream()
                .map(LendingPosition::coverage)
                .anyMatch(coverage -> coverage == LendingCoverage.PARTIAL);

        List<LendingDetectionMode> detectionModes = positions.stream()
                .map(LendingPosition::detectionMode)
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new),
                        List::copyOf));

        return new LendingPositionSummary(
                positions.size(),
                visibleSupplyUsd,
                visibleDebtUsd,
                visibleNetUsd.compareTo(BigDecimal.ZERO) == 0 ? visibleSupplyUsd.subtract(visibleDebtUsd).setScale(2, RoundingMode.HALF_UP) : visibleNetUsd,
                partialCoverage,
                detectionModes);
    }
}
