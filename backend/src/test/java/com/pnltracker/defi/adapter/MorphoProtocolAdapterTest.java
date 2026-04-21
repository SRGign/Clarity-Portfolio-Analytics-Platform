package com.pnltracker.defi.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.pnltracker.config.PortfolioProperties;
import com.pnltracker.defi.model.DefiCoverage;
import com.pnltracker.defi.model.DefiDetectionMode;
import com.pnltracker.defi.model.DefiPosition;
import com.pnltracker.defi.model.DefiPositionSide;
import com.pnltracker.defi.registry.ProtocolRegistry;

class MorphoProtocolAdapterTest {

    @Test
    void mapsMorphoCollateralAndBorrowIntoFullCoverageProtocolPositions() {
        PortfolioProperties properties = new PortfolioProperties();
        properties.getDefi().setProtocols(List.of(protocol("morpho", "Morpho", "eth-mainnet")));

        MorphoProtocolClient client = (addresses, networks) -> List.of(
                new MorphoProtocolClient.MorphoMarketPosition(
                        "0xAbC",
                        "eth-mainnet",
                        "0xmarket",
                        "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48",
                        "USDC",
                        "USD Coin",
                        6,
                        "0x2260fac5e5542a773aa44fbcfedf7c193bc2c599",
                        "WBTC",
                        "Wrapped Bitcoin",
                        8,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        new BigDecimal("20"),
                        new BigDecimal("20"),
                        new BigDecimal("0.001"),
                        new BigDecimal("70.84")));

        MorphoProtocolAdapter adapter = new MorphoProtocolAdapter(new ProtocolRegistry(properties), client);

        List<DefiPosition> positions = adapter.detectPositions(List.of("0xabc"), List.of("eth-mainnet"), List.of());

        assertThat(positions).hasSize(2);
        assertThat(positions).extracting(DefiPosition::positionSide)
                .containsExactlyInAnyOrder(DefiPositionSide.SUPPLY, DefiPositionSide.DEBT);
        assertThat(positions).extracting(DefiPosition::detectionMode)
                .containsOnly(DefiDetectionMode.PROTOCOL);
        assertThat(positions).extracting(DefiPosition::coverage)
                .containsOnly(DefiCoverage.FULL);

        DefiPosition collateral = positions.stream()
                .filter(position -> position.positionSide() == DefiPositionSide.SUPPLY)
                .findFirst()
                .orElseThrow();
        DefiPosition debt = positions.stream()
                .filter(position -> position.positionSide() == DefiPositionSide.DEBT)
                .findFirst()
                .orElseThrow();

        assertThat(collateral.underlyingSymbol()).isEqualTo("WBTC");
        assertThat(collateral.grossSupplyUsd()).isEqualByComparingTo("70.84");
        assertThat(collateral.alreadyCountedInPortfolio()).isFalse();

        assertThat(debt.underlyingSymbol()).isEqualTo("USDC");
        assertThat(debt.grossDebtUsd()).isEqualByComparingTo("20.00");
        assertThat(debt.netUsd()).isEqualByComparingTo("-20.00");
    }

    private static PortfolioProperties.ProtocolProperties protocol(String protocolKey, String protocolName, String network) {
        PortfolioProperties.ProtocolNetworkProperties protocolNetwork = new PortfolioProperties.ProtocolNetworkProperties();
        protocolNetwork.setNetwork(network);

        PortfolioProperties.ProtocolProperties protocol = new PortfolioProperties.ProtocolProperties();
        protocol.setProtocolKey(protocolKey);
        protocol.setProtocolName(protocolName);
        protocol.setNetworks(List.of(protocolNetwork));
        return protocol;
    }
}
