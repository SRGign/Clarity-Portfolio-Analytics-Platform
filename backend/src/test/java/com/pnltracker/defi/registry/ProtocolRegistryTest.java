package com.pnltracker.defi.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.pnltracker.config.PortfolioProperties;
import com.pnltracker.defi.model.DefiPositionType;

class ProtocolRegistryTest {

    @Test
    void loadsWrapperTokensFromDefiProtocolRegistry() {
        PortfolioProperties properties = new PortfolioProperties();
        properties.getDefi().setProtocols(List.of(protocol(
                "aave",
                "Aave",
                "base-mainnet",
                wrapper("0x123", null, "USDC", "USD Coin", false))));

        ProtocolRegistry registry = new ProtocolRegistry(properties);

        ProtocolRegistry.WrapperTokenDefinition wrapper = registry.findWrapperToken("base-mainnet", "0x123");
        assertThat(wrapper).isNotNull();
        assertThat(wrapper.protocolKey()).isEqualTo("aave");
        assertThat(wrapper.protocolName()).isEqualTo("Aave");
        assertThat(wrapper.positionType()).isEqualTo(DefiPositionType.LENDING);
        assertThat(wrapper.alreadyCountedInSpotTotals()).isFalse();
    }

    @Test
    void loadsCustomWrapperPositionTypeFromDefiProtocolRegistry() {
        PortfolioProperties.WrapperTokenProperties wrapper = wrapper("0xstaking", "0xstaking", "stkGHO", "stk GHO", true);
        wrapper.setPositionType("staking");

        PortfolioProperties properties = new PortfolioProperties();
        properties.getDefi().setProtocols(List.of(protocol("aave", "Aave", "eth-mainnet", wrapper)));

        ProtocolRegistry registry = new ProtocolRegistry(properties);

        assertThat(registry.findWrapperToken("eth-mainnet", "0xstaking").positionType())
                .isEqualTo(DefiPositionType.STAKING);
    }

    @Test
    void loadsDebtTokensFromDefiProtocolRegistry() {
        PortfolioProperties properties = new PortfolioProperties();
        PortfolioProperties.ProtocolNetworkProperties network = new PortfolioProperties.ProtocolNetworkProperties();
        network.setNetwork("base-mainnet");
        network.setDebtTokens(List.of(debtToken("0xdebt", "0xusdc", "USDC", "USD Coin")));

        PortfolioProperties.ProtocolProperties protocol = new PortfolioProperties.ProtocolProperties();
        protocol.setProtocolKey("aave");
        protocol.setProtocolName("Aave");
        protocol.setNetworks(List.of(network));
        properties.getDefi().setProtocols(List.of(protocol));

        ProtocolRegistry registry = new ProtocolRegistry(properties);

        ProtocolRegistry.DebtTokenDefinition debtToken = registry.findDebtToken("base-mainnet", "0xdebt");
        assertThat(debtToken).isNotNull();
        assertThat(debtToken.protocolKey()).isEqualTo("aave");
        assertThat(debtToken.underlyingSymbol()).isEqualTo("USDC");
    }

    @Test
    void fallsBackToLegacyLendingWrapperTokens() {
        PortfolioProperties properties = new PortfolioProperties();
        PortfolioProperties.WrapperTokenProperties wrapper = wrapper("0xabc", "0xusdc", "USDC", "USD Coin", true);
        wrapper.setNetwork("base-mainnet");
        wrapper.setProtocolKey("aave");
        wrapper.setProtocolName("Aave");
        properties.getLending().setWrapperTokens(List.of(wrapper));

        ProtocolRegistry registry = new ProtocolRegistry(properties);

        assertThat(registry.findWrapperToken("base-mainnet", "0xabc")).isNotNull();
    }

    @Test
    void failsFastOnDuplicateWrapperKeysWithDifferentDefinitions() {
        PortfolioProperties properties = new PortfolioProperties();
        properties.getDefi().setProtocols(List.of(
                protocol("aave", "Aave", "base-mainnet", wrapper("0x123", "0xusdc", "USDC", "USD Coin", true)),
                protocol("spark", "Spark", "base-mainnet", wrapper("0x123", "0xusdt", "USDT", "Tether", true))));

        assertThatThrownBy(() -> new ProtocolRegistry(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate wrapper token registry entry");
    }

    @Test
    void failsFastOnMissingRequiredFields() {
        PortfolioProperties properties = new PortfolioProperties();
        properties.getDefi().setProtocols(List.of(protocol(
                "aave",
                "Aave",
                "",
                wrapper("0x123", "0xusdc", "USDC", "USD Coin", true))));

        assertThatThrownBy(() -> new ProtocolRegistry(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Missing required registry field");
    }

    private static PortfolioProperties.ProtocolProperties protocol(
            String protocolKey,
            String protocolName,
            String network,
            PortfolioProperties.WrapperTokenProperties wrapper) {
        PortfolioProperties.ProtocolNetworkProperties protocolNetwork = new PortfolioProperties.ProtocolNetworkProperties();
        protocolNetwork.setNetwork(network);
        protocolNetwork.setWrapperTokens(List.of(wrapper));

        PortfolioProperties.ProtocolProperties protocol = new PortfolioProperties.ProtocolProperties();
        protocol.setProtocolKey(protocolKey);
        protocol.setProtocolName(protocolName);
        protocol.setNetworks(List.of(protocolNetwork));
        return protocol;
    }

    private static PortfolioProperties.WrapperTokenProperties wrapper(
            String tokenAddress,
            String underlyingTokenAddress,
            String underlyingSymbol,
            String underlyingName,
            boolean alreadyCountedInSpotTotals) {
        PortfolioProperties.WrapperTokenProperties wrapper = new PortfolioProperties.WrapperTokenProperties();
        wrapper.setTokenAddress(tokenAddress);
        wrapper.setUnderlyingTokenAddress(underlyingTokenAddress);
        wrapper.setUnderlyingSymbol(underlyingSymbol);
        wrapper.setUnderlyingName(underlyingName);
        wrapper.setAlreadyCountedInSpotTotals(alreadyCountedInSpotTotals);
        return wrapper;
    }

    private static PortfolioProperties.DebtTokenProperties debtToken(
            String tokenAddress,
            String underlyingTokenAddress,
            String underlyingSymbol,
            String underlyingName) {
        PortfolioProperties.DebtTokenProperties debtToken = new PortfolioProperties.DebtTokenProperties();
        debtToken.setTokenAddress(tokenAddress);
        debtToken.setUnderlyingTokenAddress(underlyingTokenAddress);
        debtToken.setUnderlyingSymbol(underlyingSymbol);
        debtToken.setUnderlyingName(underlyingName);
        return debtToken;
    }
}
