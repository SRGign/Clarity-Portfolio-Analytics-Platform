package com.pnltracker.service;

import java.util.Locale;

import com.pnltracker.provider.ProviderAsset;

final class AssetIds {

    private AssetIds() {
    }

    static String fromProviderAsset(ProviderAsset providerAsset) {
        String tokenAddress = providerAsset.tokenAddress() == null
                ? "native"
                : providerAsset.tokenAddress().toLowerCase(Locale.ROOT);
        return providerAsset.network() + ":" + tokenAddress;
    }
}
