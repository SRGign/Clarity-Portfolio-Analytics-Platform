package com.pnltracker.domain;

import java.math.BigDecimal;

public record WalletAllocation(
        String walletAddress,
        BigDecimal valueUsd) {
}
