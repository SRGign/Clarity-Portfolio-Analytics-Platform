package com.pnltracker.defi.service;

import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.pnltracker.defi.adapter.DefiCompatibilityMapper;
import com.pnltracker.defi.adapter.ProtocolPositionAdapter;
import com.pnltracker.defi.model.DefiPosition;
import com.pnltracker.defi.model.DefiPositionSummary;
import com.pnltracker.domain.LendingPosition;
import com.pnltracker.domain.LendingPositionSummary;
import com.pnltracker.provider.ProviderAsset;

@Service
public class DefiPositionService {

    private static final Logger log = LoggerFactory.getLogger(DefiPositionService.class);

    private final List<ProtocolPositionAdapter> adapters;

    public DefiPositionService(List<ProtocolPositionAdapter> adapters) {
        this.adapters = adapters == null
                ? List.of()
                : adapters.stream()
                        .sorted(Comparator.comparingInt(ProtocolPositionAdapter::order))
                        .toList();
    }

    public DefiDetectionResult detect(List<String> addresses, List<String> networks, List<ProviderAsset> providerAssets) {
        List<DefiPosition> defiPositions = adapters.stream()
                .flatMap(adapter -> detectSafely(adapter, addresses, networks, providerAssets).stream())
                .toList();

        List<LendingPosition> lendingPositions = defiPositions.stream()
                .map(DefiCompatibilityMapper::toLendingPosition)
                .toList();

        return new DefiDetectionResult(
                defiPositions,
                DefiPositionSummary.fromPositions(defiPositions),
                lendingPositions,
                LendingPositionSummary.fromPositions(lendingPositions));
    }

    private List<DefiPosition> detectSafely(
            ProtocolPositionAdapter adapter,
            List<String> addresses,
            List<String> networks,
            List<ProviderAsset> providerAssets) {
        try {
            return adapter.detectPositions(addresses, networks, providerAssets);
        } catch (RuntimeException exception) {
            log.warn("DeFi adapter {} failed and was skipped: {}", adapter.adapterId(), exception.getMessage());
            return List.of();
        }
    }
}
