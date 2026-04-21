package com.pnltracker.provider;

import java.util.List;

public record ProviderFetchResult(
        List<ProviderAsset> assets,
        List<ProviderExchange> exchanges,
        List<String> skippedNetworks) {
}
