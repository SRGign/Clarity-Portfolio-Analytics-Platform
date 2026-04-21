package com.pnltracker.defi.model;

import java.util.Locale;

public enum DefiPositionType {
    LENDING,
    STAKING,
    VAULT;

    public String apiValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
