package com.pnltracker.defi.adapter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
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
public class MorphoProtocolAdapter implements ProtocolPositionAdapter {

    private final ProtocolRegistry protocolRegistry;
    private final MorphoProtocolClient morphoProtocolClient;

    public MorphoProtocolAdapter(ProtocolRegistry protocolRegistry, MorphoProtocolClient morphoProtocolClient) {
        this.protocolRegistry = protocolRegistry;
        this.morphoProtocolClient = morphoProtocolClient;
    }

    @Override
    public String adapterId() {
        return "morpho-protocol";
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public List<DefiPosition> detectPositions(List<String> addresses, List<String> networks, List<com.pnltracker.provider.ProviderAsset> providerAssets) {
        if (!protocolRegistry.supportsProtocol("morpho")) {
            return List.of();
        }

        return morphoProtocolClient.fetchPositions(addresses, networks).stream()
                .flatMap(position -> toDefiPositions(position).stream())
                .toList();
    }

    private List<DefiPosition> toDefiPositions(MorphoProtocolClient.MorphoMarketPosition position) {
        List<DefiPosition> positions = new ArrayList<>();

        if (positive(position.collateralUsd())) {
            BigDecimal collateralUsd = scaleUsd(position.collateralUsd());
            BigDecimal priceUsd = derivePrice(position.collateralUsd(), position.collateralQuantity());
            positions.add(new DefiPosition(
                    positionId(position.network(), position.walletAddress(), position.marketId(), "collateral"),
                    normalizeWallet(position.walletAddress()),
                    position.network(),
                    "morpho",
                    "Morpho",
                    DefiPositionType.LENDING,
                    DefiPositionSide.SUPPLY,
                    DefiDetectionMode.PROTOCOL,
                    DefiCoverage.FULL,
                    sourceAssetId(position.network(), position.collateralTokenAddress()),
                    position.collateralTokenAddress(),
                    valueOrFallback(position.collateralTokenSymbol(), "UNKNOWN"),
                    valueOrFallback(position.collateralTokenName(), position.collateralTokenSymbol()),
                    scaleQuantity(position.collateralQuantity()),
                    priceUsd,
                    collateralUsd,
                    zero(),
                    collateralUsd,
                    false,
                    List.of(new DefiExposureLeg(
                            sourceAssetId(position.network(), position.collateralTokenAddress()),
                            position.collateralTokenAddress(),
                            valueOrFallback(position.collateralTokenSymbol(), "UNKNOWN"),
                            valueOrFallback(position.collateralTokenName(), position.collateralTokenSymbol()),
                            scaleQuantity(position.collateralQuantity()),
                            priceUsd,
                            collateralUsd,
                            false))));
        }

        if (positive(position.supplyUsd())) {
            BigDecimal supplyUsd = scaleUsd(position.supplyUsd());
            BigDecimal priceUsd = derivePrice(position.supplyUsd(), position.supplyQuantity());
            positions.add(new DefiPosition(
                    positionId(position.network(), position.walletAddress(), position.marketId(), "supply"),
                    normalizeWallet(position.walletAddress()),
                    position.network(),
                    "morpho",
                    "Morpho",
                    DefiPositionType.LENDING,
                    DefiPositionSide.SUPPLY,
                    DefiDetectionMode.PROTOCOL,
                    DefiCoverage.FULL,
                    sourceAssetId(position.network(), position.loanTokenAddress()),
                    position.loanTokenAddress(),
                    valueOrFallback(position.loanTokenSymbol(), "UNKNOWN"),
                    valueOrFallback(position.loanTokenName(), position.loanTokenSymbol()),
                    scaleQuantity(position.supplyQuantity()),
                    priceUsd,
                    supplyUsd,
                    zero(),
                    supplyUsd,
                    false,
                    List.of(new DefiExposureLeg(
                            sourceAssetId(position.network(), position.loanTokenAddress()),
                            position.loanTokenAddress(),
                            valueOrFallback(position.loanTokenSymbol(), "UNKNOWN"),
                            valueOrFallback(position.loanTokenName(), position.loanTokenSymbol()),
                            scaleQuantity(position.supplyQuantity()),
                            priceUsd,
                            supplyUsd,
                            false))));
        }

        if (positive(position.borrowUsd())) {
            BigDecimal borrowUsd = scaleUsd(position.borrowUsd());
            BigDecimal priceUsd = derivePrice(position.borrowUsd(), position.borrowQuantity());
            positions.add(new DefiPosition(
                    positionId(position.network(), position.walletAddress(), position.marketId(), "borrow"),
                    normalizeWallet(position.walletAddress()),
                    position.network(),
                    "morpho",
                    "Morpho",
                    DefiPositionType.LENDING,
                    DefiPositionSide.DEBT,
                    DefiDetectionMode.PROTOCOL,
                    DefiCoverage.FULL,
                    sourceAssetId(position.network(), position.loanTokenAddress()),
                    position.loanTokenAddress(),
                    valueOrFallback(position.loanTokenSymbol(), "UNKNOWN"),
                    valueOrFallback(position.loanTokenName(), position.loanTokenSymbol()),
                    scaleQuantity(position.borrowQuantity()),
                    priceUsd,
                    zero(),
                    borrowUsd,
                    borrowUsd.negate(),
                    false,
                    List.of(new DefiExposureLeg(
                            sourceAssetId(position.network(), position.loanTokenAddress()),
                            position.loanTokenAddress(),
                            valueOrFallback(position.loanTokenSymbol(), "UNKNOWN"),
                            valueOrFallback(position.loanTokenName(), position.loanTokenSymbol()),
                            scaleQuantity(position.borrowQuantity()),
                            priceUsd,
                            borrowUsd.negate(),
                            false))));
        }

        return List.copyOf(positions);
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    private BigDecimal derivePrice(BigDecimal usdValue, BigDecimal quantity) {
        if (usdValue == null || quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
        }
        return usdValue.divide(quantity, 8, RoundingMode.HALF_UP);
    }

    private BigDecimal scaleUsd(BigDecimal value) {
        return value == null ? zero() : value.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal scaleQuantity(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.stripTrailingZeros();
    }

    private BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }

    private String sourceAssetId(String network, String tokenAddress) {
        return network + ":" + tokenAddress.toLowerCase(Locale.ROOT);
    }

    private String positionId(String network, String walletAddress, String marketId, String suffix) {
        return network + ":" + walletAddress + ":morpho:" + marketId.toLowerCase(Locale.ROOT) + ":" + suffix;
    }

    private String normalizeWallet(String walletAddress) {
        return walletAddress == null ? "" : walletAddress.toLowerCase(Locale.ROOT);
    }

    private String valueOrFallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
