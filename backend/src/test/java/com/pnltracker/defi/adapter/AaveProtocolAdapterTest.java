package com.pnltracker.defi.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.pnltracker.config.PortfolioProperties;
import com.pnltracker.defi.model.DefiPosition;
import com.pnltracker.defi.model.DefiPositionSide;
import com.pnltracker.defi.model.DefiDetectionMode;
import com.pnltracker.defi.model.DefiCoverage;
import com.pnltracker.defi.registry.ProtocolRegistry;

class AaveProtocolAdapterTest {

    @Test
    void mapsProtocolClientReserveIntoFullCoverageSupplyAndDebtPositions() {
        PortfolioProperties properties = new PortfolioProperties();
        properties.getDefi().setProtocols(List.of(protocol("aave", "Aave", "base-mainnet")));

        AaveProtocolClient client = (addresses, networks) -> List.of(
                new AaveProtocolClient.AaveUserReservePosition(
                        "0xAbC",
                        "base-mainnet",
                        "aave",
                        "Aave",
                        "0xusdc",
                        "USDC",
                        "USD Coin",
                        new BigDecimal("100"),
                        BigDecimal.ONE,
                        new BigDecimal("100.00"),
                        new BigDecimal("40"),
                        new BigDecimal("40.00"),
                        true));

        AaveProtocolAdapter adapter = new AaveProtocolAdapter(new ProtocolRegistry(properties), client);

        List<DefiPosition> positions = adapter.detectPositions(List.of("0xabc"), List.of("base-mainnet"), List.of());

        assertThat(positions).hasSize(2);
        assertThat(positions).extracting(DefiPosition::positionSide)
                .containsExactlyInAnyOrder(DefiPositionSide.SUPPLY, DefiPositionSide.DEBT);
        assertThat(positions).extracting(DefiPosition::detectionMode)
                .containsOnly(DefiDetectionMode.PROTOCOL);
        assertThat(positions).extracting(DefiPosition::coverage)
                .containsOnly(DefiCoverage.FULL);

        DefiPosition supply = positions.stream().filter(position -> position.positionSide() == DefiPositionSide.SUPPLY).findFirst().orElseThrow();
        DefiPosition debt = positions.stream().filter(position -> position.positionSide() == DefiPositionSide.DEBT).findFirst().orElseThrow();

        assertThat(supply.walletAddress()).isEqualTo("0xabc");
        assertThat(supply.grossSupplyUsd()).isEqualByComparingTo("100.00");
        assertThat(supply.netUsd()).isEqualByComparingTo("100.00");
        assertThat(supply.alreadyCountedInPortfolio()).isTrue();

        assertThat(debt.walletAddress()).isEqualTo("0xabc");
        assertThat(debt.grossDebtUsd()).isEqualByComparingTo("40.00");
        assertThat(debt.netUsd()).isEqualByComparingTo("-40.00");
        assertThat(debt.alreadyCountedInPortfolio()).isFalse();
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
