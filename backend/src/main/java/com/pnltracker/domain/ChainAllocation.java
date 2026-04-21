package com.pnltracker.domain;

import java.math.BigDecimal;

public record ChainAllocation(String network, String displayName, BigDecimal valueUsd) {
}
