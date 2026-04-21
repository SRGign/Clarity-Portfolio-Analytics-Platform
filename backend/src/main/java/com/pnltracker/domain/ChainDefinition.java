package com.pnltracker.domain;

public record ChainDefinition(
        String id,
        String providerNetwork,
        String displayName,
        String family,
        boolean enabled) {
}
