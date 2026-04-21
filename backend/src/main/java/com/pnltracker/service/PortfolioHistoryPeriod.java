package com.pnltracker.service;

public enum PortfolioHistoryPeriod {
    H24("24h", 1),
    D7("7d", 7),
    D30("30d", 30);

    private final String apiValue;
    private final int lookbackDays;

    PortfolioHistoryPeriod(String apiValue, int lookbackDays) {
        this.apiValue = apiValue;
        this.lookbackDays = lookbackDays;
    }

    public String apiValue() {
        return apiValue;
    }

    public int lookbackDays() {
        return lookbackDays;
    }

    public static PortfolioHistoryPeriod fromApiValue(String value) {
        for (PortfolioHistoryPeriod period : values()) {
            if (period.apiValue.equalsIgnoreCase(value)) {
                return period;
            }
        }
        throw new IllegalArgumentException("Unsupported history period: " + value);
    }
}
