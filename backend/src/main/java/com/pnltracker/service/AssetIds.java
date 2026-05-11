package com.pnltracker.service;

import java.util.Locale;

import com.pnltracker.provider.ProviderAsset;

final class AssetIds {

    private AssetIds() {
    }

    static String fromProviderAsset(ProviderAsset providerAsset) {
        String tokenAddress = providerAsset.tokenAddress() == null
                ? "native"
                : normalizeTokenAddress(providerAsset.network(), providerAsset.tokenAddress());
        return providerAsset.network() + ":" + tokenAddress;
    }

    private static String normalizeTokenAddress(String network, String tokenAddress) {
        if ("solana-mainnet".equalsIgnoreCase(network)) {
            return tokenAddress;
        }
        return tokenAddress.toLowerCase(Locale.ROOT);
    }
}
