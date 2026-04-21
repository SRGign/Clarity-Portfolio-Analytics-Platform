package com.pnltracker.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.pnltracker.defi.registry.ProtocolRegistry;
import com.pnltracker.domain.LendingCoverage;
import com.pnltracker.domain.LendingDetectionMode;
import com.pnltracker.domain.LendingPosition;
import com.pnltracker.domain.LendingPositionSide;
import com.pnltracker.provider.ProviderAsset;

@Component
public class WrapperHeuristicLendingDetector implements LendingPositionDetector {

    private static final BigDecimal MIN_VISIBLE_VALUE_USD = new BigDecimal("1");

    private final ProtocolRegistry protocolRegistry;
    private final com.pnltracker.config.PortfolioProperties properties;

    @Autowired
    public WrapperHeuristicLendingDetector(
            ProtocolRegistry protocolRegistry,
            com.pnltracker.config.PortfolioProperties properties) {
        this.protocolRegistry = protocolRegistry;
        this.properties = properties;
    }

    public WrapperHeuristicLendingDetector(ProtocolRegistry protocolRegistry) {
        this(protocolRegistry, null);
    }

    public WrapperHeuristicLendingDetector(com.pnltracker.config.PortfolioProperties properties) {
        this(new ProtocolRegistry(properties), properties);
    }

    @Override
    public List<LendingPosition> detectPositions(List<ProviderAsset> providerAssets) {
        if (providerAssets == null || providerAssets.isEmpty()) {
            return List.of();
        }

        if (protocolRegistry.wrapperTokens().isEmpty()) {
            return List.of();
        }

        return providerAssets.stream()
                .map(asset -> toPosition(asset, protocolRegistry.findWrapperToken(asset.network(), asset.tokenAddress())))
                .filter(position -> position != null)
                .toList();
    }

    private LendingPosition toPosition(ProviderAsset asset, ProtocolRegistry.WrapperTokenDefinition wrapper) {
        if (asset == null || wrapper == null || asset.priceUsd() == null) {
            return null;
        }

        BigDecimal supplyUsd = asset.quantity()
                .multiply(asset.priceUsd())
                .setScale(2, RoundingMode.HALF_UP);
        if (supplyUsd.compareTo(MIN_VISIBLE_VALUE_USD) < 0) {
            return null;
        }

        String normalizedWallet = asset.walletAddress().toLowerCase(Locale.ROOT);
        return new LendingPosition(
                positionId(asset.network(), normalizedWallet, wrapper.protocolKey(), asset.tokenAddress()),
                normalizedWallet,
                asset.network(),
                wrapper.protocolKey(),
                wrapper.protocolName(),
                LendingPositionSide.SUPPLY,
                LendingDetectionMode.HEURISTIC,
                LendingCoverage.PARTIAL,
                AssetIds.fromProviderAsset(asset),
                wrapper.underlyingTokenAddress(),
                wrapper.underlyingSymbol(),
                wrapper.underlyingName(),
                asset.quantity(),
                asset.priceUsd(),
                supplyUsd,
                null,
                null,
                wrapper.alreadyCountedInSpotTotals());
    }

    private String positionId(String network, String walletAddress, String protocolKey, String tokenAddress) {
        return network
                + ":" + walletAddress
                + ":" + protocolKey.toLowerCase(Locale.ROOT)
                + ":" + tokenAddress.toLowerCase(Locale.ROOT)
                + ":supply";
    }

    com.pnltracker.config.PortfolioProperties propertiesView() {
        if (properties == null) {
            throw new IllegalStateException("Legacy properties are not available for this detector instance");
        }
        return properties;
    }
}
