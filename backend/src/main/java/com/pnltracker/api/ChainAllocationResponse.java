package com.pnltracker.api;

import java.math.BigDecimal;

public record ChainAllocationResponse(String network, String displayName, BigDecimal valueUsd) {
}
