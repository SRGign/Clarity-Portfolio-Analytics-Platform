package com.pnltracker.provider;

import java.util.List;

public record ProviderExchange(
        List<String> addresses,
        List<String> networks,
        String pageKey,
        String requestBody,
        String responseBody) {
}
