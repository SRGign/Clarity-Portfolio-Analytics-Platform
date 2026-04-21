package com.pnltracker.defi.model;

import java.util.Locale;

public enum DefiPositionSide {
    SUPPLY,
    DEBT,
    NET;

    public String apiValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
