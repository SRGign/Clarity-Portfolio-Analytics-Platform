package com.pnltracker.analytics;

import java.util.List;

public record BenchmarkData(
        List<BenchmarkPoint> bitcoin,
        List<BenchmarkPoint> solana) {
}
