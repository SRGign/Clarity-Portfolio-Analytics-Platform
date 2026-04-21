package com.pnltracker.defi.adapter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.pnltracker.defi.model.DefiCoverage;
import com.pnltracker.defi.model.DefiDetectionMode;
import com.pnltracker.defi.model.DefiExposureLeg;
import com.pnltracker.defi.model.DefiPosition;
import com.pnltracker.defi.model.DefiPositionSide;
import com.pnltracker.defi.model.DefiPositionType;
import com.pnltracker.defi.registry.ProtocolRegistry;
import com.pnltracker.provider.ProviderAsset;

@Component
public class WrapperHeuristicDefiAdapter implements ProtocolPositionAdapter {

    private static final BigDecimal MIN_VISIBLE_VALUE_USD = new BigDecimal("1");

    private final ProtocolRegistry protocolRegistry;

    public WrapperHeuristicDefiAdapter(ProtocolRegistry protocolRegistry) {
        this.protocolRegistry = protocolRegistry;
    }

    @Override
    public String adapterId() {
        return "wrapper-heuristic";
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public List<DefiPosition> detectPositions(List<String> addresses, List<String> networks, List<ProviderAsset> providerAssets) {
        return providerAssets.stream()
                .map(this::toPosition)
                .filter(position -> position != null)
                .toList();
    }

    private DefiPosition toPosition(ProviderAsset asset) {
        if (asset == null || asset.priceUsd() == null || asset.tokenAddress() == null) {
            return null;
        }

        ProtocolRegistry.WrapperTokenDefinition wrapper = protocolRegistry.findWrapperToken(asset.network(), asset.tokenAddress());
        if (wrapper != null) {
            return toSupplyPosition(asset, wrapper);
        }

        ProtocolRegistry.DebtTokenDefinition debtToken = protocolRegistry.findDebtToken(asset.network(), asset.tokenAddress());
        if (debtToken != null) {
            return toDebtPosition(asset, debtToken);
        }

        return null;
    }

    private DefiPosition toSupplyPosition(ProviderAsset asset, ProtocolRegistry.WrapperTokenDefinition wrapper) {
        BigDecimal supplyUsd = asset.quantity().multiply(asset.priceUsd()).setScale(2, RoundingMode.HALF_UP);
        if (supplyUsd.compareTo(MIN_VISIBLE_VALUE_USD) < 0) {
            return null;
        }

        String normalizedWallet = asset.walletAddress().toLowerCase(Locale.ROOT);
        String sourceAssetId = sourceAssetId(asset);
        return new DefiPosition(
                positionId(asset.network(), normalizedWallet, wrapper.protocolKey(), asset.tokenAddress(), "supply"),
                normalizedWallet,
                asset.network(),
                wrapper.protocolKey(),
                wrapper.protocolName(),
                wrapper.positionType(),
                DefiPositionSide.SUPPLY,
                DefiDetectionMode.HEURISTIC,
                DefiCoverage.PARTIAL,
                sourceAssetId,
                wrapper.underlyingTokenAddress(),
                wrapper.underlyingSymbol(),
                wrapper.underlyingName(),
                asset.quantity(),
                asset.priceUsd(),
                supplyUsd,
                BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                supplyUsd,
                wrapper.alreadyCountedInSpotTotals(),
                List.of(new DefiExposureLeg(
                        sourceAssetId,
                        wrapper.underlyingTokenAddress(),
                        wrapper.underlyingSymbol(),
                        wrapper.underlyingName(),
                        asset.quantity(),
                        asset.priceUsd(),
                        supplyUsd,
                        wrapper.alreadyCountedInSpotTotals())));
    }

    private DefiPosition toDebtPosition(ProviderAsset asset, ProtocolRegistry.DebtTokenDefinition debtToken) {
        BigDecimal debtUsd = asset.quantity().multiply(asset.priceUsd()).setScale(2, RoundingMode.HALF_UP);
        if (debtUsd.compareTo(MIN_VISIBLE_VALUE_USD) < 0) {
            return null;
        }

        String normalizedWallet = asset.walletAddress().toLowerCase(Locale.ROOT);
        String sourceAssetId = sourceAssetId(asset);
        BigDecimal zero = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        return new DefiPosition(
                positionId(asset.network(), normalizedWallet, debtToken.protocolKey(), asset.tokenAddress(), "debt"),
                normalizedWallet,
                asset.network(),
                debtToken.protocolKey(),
                debtToken.protocolName(),
                DefiPositionType.LENDING,
                DefiPositionSide.DEBT,
                DefiDetectionMode.HEURISTIC,
                DefiCoverage.PARTIAL,
                sourceAssetId,
                debtToken.underlyingTokenAddress(),
                debtToken.underlyingSymbol(),
                debtToken.underlyingName(),
                asset.quantity(),
                asset.priceUsd(),
                zero,
                debtUsd,
                debtUsd.negate().setScale(2, RoundingMode.HALF_UP),
                false,
                List.of(new DefiExposureLeg(
                        sourceAssetId,
                        debtToken.underlyingTokenAddress(),
                        debtToken.underlyingSymbol(),
                        debtToken.underlyingName(),
                        asset.quantity(),
                        asset.priceUsd(),
                        debtUsd.negate().setScale(2, RoundingMode.HALF_UP),
                        false)));
    }

    private String positionId(String network, String walletAddress, String protocolKey, String tokenAddress, String suffix) {
        return network
                + ":" + walletAddress
                + ":" + protocolKey.toLowerCase(Locale.ROOT)
                + ":" + tokenAddress.toLowerCase(Locale.ROOT)
                + ":" + suffix;
    }

    private String sourceAssetId(ProviderAsset asset) {
        String tokenAddress = asset.tokenAddress() == null
                ? "native"
                : asset.tokenAddress().toLowerCase(Locale.ROOT);
        return asset.network() + ":" + tokenAddress;
    }
}
