package com.pnltracker.ai;

public record TokenExposure(
        String symbol,
        double totalUsd,
        double spotUsd,
        double defiUsd,
        double sharePct) {
}
