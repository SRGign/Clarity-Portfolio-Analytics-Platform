package com.pnltracker.defi.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.pnltracker.config.PortfolioProperties;
import com.pnltracker.defi.model.DefiPosition;
import com.pnltracker.defi.model.DefiPositionSide;
import com.pnltracker.defi.registry.ProtocolRegistry;
import com.pnltracker.provider.ProviderAsset;

class WrapperHeuristicDefiAdapterTest {

    @Test
    void detectsDebtTokenAsNegativeDefiPositionFromProtocolRegistry() {
        PortfolioProperties properties = new PortfolioProperties();
        PortfolioProperties.ProtocolNetworkProperties network = new PortfolioProperties.ProtocolNetworkProperties();
        network.setNetwork("base-mainnet");

        PortfolioProperties.DebtTokenProperties debtToken = new PortfolioProperties.DebtTokenProperties();
        debtToken.setTokenAddress("0xdebt");
        debtToken.setUnderlyingTokenAddress("0xusdc");
        debtToken.setUnderlyingSymbol("USDC");
        debtToken.setUnderlyingName("USD Coin");
        network.setDebtTokens(List.of(debtToken));

        PortfolioProperties.ProtocolProperties protocol = new PortfolioProperties.ProtocolProperties();
        protocol.setProtocolKey("aave");
        protocol.setProtocolName("Aave");
        protocol.setNetworks(List.of(network));
        properties.getDefi().setProtocols(List.of(protocol));

        WrapperHeuristicDefiAdapter adapter = new WrapperHeuristicDefiAdapter(new ProtocolRegistry(properties));

        List<DefiPosition> positions = adapter.detectPositions(
                List.of("0xabc"),
                List.of("base-mainnet"),
                List.of(new ProviderAsset(
                        "0xabc",
                        "base-mainnet",
                        "0xdebt",
                        "variableDebtUSDC",
                        "Variable Debt USDC",
                        6,
                        new BigDecimal("250.0"),
                        BigDecimal.ONE,
                        false,
                        null)));

        assertThat(positions).hasSize(1);
        DefiPosition position = positions.get(0);
        assertThat(position.positionSide()).isEqualTo(DefiPositionSide.DEBT);
        assertThat(position.grossDebtUsd()).isEqualByComparingTo("250.00");
        assertThat(position.netUsd()).isEqualByComparingTo("-250.00");
        assertThat(position.alreadyCountedInPortfolio()).isFalse();
    }
}
