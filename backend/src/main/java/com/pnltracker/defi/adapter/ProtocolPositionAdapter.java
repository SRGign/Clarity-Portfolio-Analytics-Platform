package com.pnltracker.defi.adapter;

import java.util.List;

import com.pnltracker.defi.model.DefiPosition;
import com.pnltracker.provider.ProviderAsset;

public interface ProtocolPositionAdapter {

    default String adapterId() {
        return getClass().getSimpleName();
    }

    default int order() {
        return 100;
    }

    List<DefiPosition> detectPositions(List<String> addresses, List<String> networks, List<ProviderAsset> providerAssets);
}
