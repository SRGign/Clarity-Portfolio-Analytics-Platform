package com.pnltracker.provider;

import java.util.List;

public interface AssetPortfolioProvider {

    default List<ProviderAsset> fetchAssets(List<String> addresses, List<String> networks) {
        return fetchAssetsWithDebug(addresses, networks).assets();
    }

    ProviderFetchResult fetchAssetsWithDebug(List<String> addresses, List<String> networks);

    String providerName();
}
