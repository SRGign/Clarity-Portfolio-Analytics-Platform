package com.pnltracker.defi.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashSet;
import java.util.List;

public record DefiPositionSummary(
        int trackedPositions,
        BigDecimal visibleSupplyUsd,
        BigDecimal visibleDebtUsd,
        BigDecimal visibleNetUsd,
        boolean partialCoverage,
        List<DefiDetectionMode> detectionModes) {

    public static DefiPositionSummary empty() {
        return new DefiPositionSummary(
                0,
                BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                false,
                List.of());
    }

    public static DefiPositionSummary fromPositions(List<DefiPosition> positions) {
        if (positions == null || positions.isEmpty()) {
            return empty();
        }

        BigDecimal visibleSupplyUsd = positions.stream()
                .map(DefiPosition::grossSupplyUsd)
                .filter(value -> value != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal visibleDebtUsd = positions.stream()
                .map(DefiPosition::grossDebtUsd)
                .filter(value -> value != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal visibleNetUsd = positions.stream()
                .map(DefiPosition::netUsd)
                .filter(value -> value != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        boolean partialCoverage = positions.stream()
                .map(DefiPosition::coverage)
                .anyMatch(coverage -> coverage == DefiCoverage.PARTIAL);

        List<DefiDetectionMode> detectionModes = positions.stream()
                .map(DefiPosition::detectionMode)
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new),
                        List::copyOf));

        return new DefiPositionSummary(
                positions.size(),
                visibleSupplyUsd,
                visibleDebtUsd,
                visibleNetUsd,
                partialCoverage,
                detectionModes);
    }
}
