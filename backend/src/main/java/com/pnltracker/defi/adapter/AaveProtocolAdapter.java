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

@Component
public class AaveProtocolAdapter implements ProtocolPositionAdapter {

    private final ProtocolRegistry protocolRegistry;
    private final AaveProtocolClient aaveProtocolClient;

    public AaveProtocolAdapter(ProtocolRegistry protocolRegistry, AaveProtocolClient aaveProtocolClient) {
        this.protocolRegistry = protocolRegistry;
        this.aaveProtocolClient = aaveProtocolClient;
    }

    @Override
    public String adapterId() {
        return "aave-protocol";
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public List<DefiPosition> detectPositions(List<String> addresses, List<String> networks, List<com.pnltracker.provider.ProviderAsset> providerAssets) {
        boolean hasAaveSignals = protocolRegistry.supportsProtocol("aave");
        if (!hasAaveSignals) {
            return List.of();
        }

        return aaveProtocolClient.fetchPositions(addresses, networks).stream()
                .flatMap(position -> toDefiPositions(position).stream())
                .toList();
    }

    private List<DefiPosition> toDefiPositions(AaveProtocolClient.AaveUserReservePosition position) {
        DefiPosition supplyPosition = toSupplyPosition(position);
        DefiPosition debtPosition = toDebtPosition(position);

        if (supplyPosition != null && debtPosition != null) {
            return List.of(supplyPosition, debtPosition);
        }
        if (supplyPosition != null) {
            return List.of(supplyPosition);
        }
        if (debtPosition != null) {
            return List.of(debtPosition);
        }
        return List.of();
    }

    private DefiPosition toSupplyPosition(AaveProtocolClient.AaveUserReservePosition position) {
        if (position.supplyUsd() == null || position.supplyUsd().compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }

        BigDecimal supplyUsd = position.supplyUsd().setScale(2, RoundingMode.HALF_UP);
        String walletAddress = normalizeWallet(position.walletAddress());
        return new DefiPosition(
                positionId(position.network(), walletAddress, position.protocolKey(), position.underlyingTokenAddress(), "protocol-supply"),
                walletAddress,
                position.network(),
                position.protocolKey(),
                position.protocolName(),
                DefiPositionType.LENDING,
                DefiPositionSide.SUPPLY,
                DefiDetectionMode.PROTOCOL,
                DefiCoverage.FULL,
                sourceAssetId(position.network(), position.underlyingTokenAddress()),
                position.underlyingTokenAddress(),
                position.underlyingSymbol(),
                position.underlyingName(),
                position.supplyQuantity(),
                position.priceUsd(),
                supplyUsd,
                zero(),
                supplyUsd,
                position.alreadyCountedInSpotTotals(),
                List.of(new DefiExposureLeg(
                        sourceAssetId(position.network(), position.underlyingTokenAddress()),
                        position.underlyingTokenAddress(),
                        position.underlyingSymbol(),
                        position.underlyingName(),
                        position.supplyQuantity(),
                        position.priceUsd(),
                        supplyUsd,
                        position.alreadyCountedInSpotTotals())));
    }

    private DefiPosition toDebtPosition(AaveProtocolClient.AaveUserReservePosition position) {
        if (position.debtUsd() == null || position.debtUsd().compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }

        BigDecimal debtUsd = position.debtUsd().setScale(2, RoundingMode.HALF_UP);
        String walletAddress = normalizeWallet(position.walletAddress());
        return new DefiPosition(
                positionId(position.network(), walletAddress, position.protocolKey(), position.underlyingTokenAddress(), "protocol-debt"),
                walletAddress,
                position.network(),
                position.protocolKey(),
                position.protocolName(),
                DefiPositionType.LENDING,
                DefiPositionSide.DEBT,
                DefiDetectionMode.PROTOCOL,
                DefiCoverage.FULL,
                sourceAssetId(position.network(), position.underlyingTokenAddress()),
                position.underlyingTokenAddress(),
                position.underlyingSymbol(),
                position.underlyingName(),
                position.debtQuantity(),
                position.priceUsd(),
                zero(),
                debtUsd,
                debtUsd.negate().setScale(2, RoundingMode.HALF_UP),
                false,
                List.of(new DefiExposureLeg(
                        sourceAssetId(position.network(), position.underlyingTokenAddress()),
                        position.underlyingTokenAddress(),
                        position.underlyingSymbol(),
                        position.underlyingName(),
                        position.debtQuantity(),
                        position.priceUsd(),
                        debtUsd.negate().setScale(2, RoundingMode.HALF_UP),
                        false)));
    }

    private String positionId(String network, String walletAddress, String protocolKey, String underlyingTokenAddress, String suffix) {
        return network
                + ":" + walletAddress
                + ":" + protocolKey.toLowerCase(Locale.ROOT)
                + ":" + underlyingTokenAddress.toLowerCase(Locale.ROOT)
                + ":" + suffix;
    }

    private String sourceAssetId(String network, String tokenAddress) {
        return network + ":" + tokenAddress.toLowerCase(Locale.ROOT);
    }

    private String normalizeWallet(String walletAddress) {
        return walletAddress == null ? "" : walletAddress.toLowerCase(Locale.ROOT);
    }

    private BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }
}
