package com.pnltracker.domain;

import java.util.Locale;

public enum LendingDetectionMode {
    HEURISTIC,
    ADAPTER;

    public String apiValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
