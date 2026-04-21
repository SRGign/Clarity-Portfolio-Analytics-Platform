package com.pnltracker.api;

import java.math.BigDecimal;

public record WalletAllocationResponse(
        String walletAddress,
        BigDecimal valueUsd) {
}
