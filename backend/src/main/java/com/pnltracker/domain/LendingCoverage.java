package com.pnltracker.domain;

import java.util.Locale;

public enum LendingCoverage {
    PARTIAL,
    COMPLETE;

    public String apiValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
