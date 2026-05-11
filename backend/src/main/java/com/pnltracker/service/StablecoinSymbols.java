package com.pnltracker.service;

import java.util.Locale;
import java.util.Set;

public final class StablecoinSymbols {

    private static final Set<String> STABLE_SYMBOLS = Set.of("USDC", "USDT", "DAI", "USDS", "BUSD", "FRAX", "LUSD");

    private StablecoinSymbols() {
    }

    public static boolean isStable(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return false;
        }
        return STABLE_SYMBOLS.contains(symbol.trim().toUpperCase(Locale.ROOT));
    }
}
