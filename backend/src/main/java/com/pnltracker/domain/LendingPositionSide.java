package com.pnltracker.domain;

import java.util.Locale;

public enum LendingPositionSide {
    SUPPLY,
    DEBT,
    NET;

    public String apiValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
