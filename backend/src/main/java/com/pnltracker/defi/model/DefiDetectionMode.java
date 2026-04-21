package com.pnltracker.defi.model;

import java.util.Locale;

public enum DefiDetectionMode {
    PROTOCOL,
    HEURISTIC;

    public String apiValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
