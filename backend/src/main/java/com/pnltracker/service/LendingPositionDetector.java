package com.pnltracker.service;

import java.util.List;

import com.pnltracker.domain.LendingPosition;
import com.pnltracker.provider.ProviderAsset;

public interface LendingPositionDetector {

    List<LendingPosition> detectPositions(List<ProviderAsset> providerAssets);
}
