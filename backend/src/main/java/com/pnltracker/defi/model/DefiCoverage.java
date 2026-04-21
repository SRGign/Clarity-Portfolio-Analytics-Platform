package com.pnltracker.defi.model;

import java.util.Locale;

public enum DefiCoverage {
    FULL,
    PARTIAL;

    public String apiValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
