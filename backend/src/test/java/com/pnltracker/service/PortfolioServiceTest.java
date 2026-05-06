package com.pnltracker.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.pnltracker.config.PortfolioProperties;
import com.pnltracker.domain.AssetBalance;
import com.pnltracker.domain.LendingPosition;
import com.pnltracker.domain.PortfolioSummary;
import com.pnltracker.provider.AssetPortfolioProvider;
import com.pnltracker.provider.ProviderAsset;
import com.pnltracker.provider.ProviderFetchResult;

class PortfolioServiceTest {

    private PortfolioService portfolioService;

    @BeforeEach
    void setUp() {
        PortfolioProperties properties = new PortfolioProperties();
        properties.getLending().setWrapperTokens(List.of(wrapper("base-mainnet", "0x123", "aave", "Aave", "0xusdc", "USDC", "USD Coin")));
        AssetPortfolioProvider provider = new StubProvider();
        portfolioService = new PortfolioService(
                provider,
                new ChainCatalogService(),
                new SimpleTtlCache(properties),
                new WrapperHeuristicLendingDetector(properties));
    }

    @Test
    void aggregatesAssetsAcrossWalletsAndFiltersSmallPositions() {
        List<AssetBalance> assets = portfolioService.getAssets(
                List.of("0xabc", "0xdef"),
                List.of("ethereum", "base"));

        assertThat(assets).hasSize(2);
        assertThat(assets.get(0).symbol()).isEqualTo("ETH");
        assertThat(assets.get(0).quantity()).isEqualByComparingTo("3.0");
        assertThat(assets.get(0).valueUsd()).isEqualByComparingTo("6000.00");
    }

    @Test
    void filtersAbsurdOutlierTokensFromSummary() {
        PortfolioSummary summary = portfolioService.getSummary(
                List.of("0xabc", "0xdef"),
                List.of("ethereum", "base"));

        assertThat(summary.totalUsd()).isEqualByComparingTo("6100.00");
        assertThat(summary.grossAssetUsd()).isEqualByComparingTo("6100.00");
        assertThat(summary.grossLiabilityUsd()).isEqualByComparingTo("0.00");
        assertThat(summary.netUsd()).isEqualByComparingTo("6100.00");
        assertThat(summary.hiddenAssets()).isEqualTo(2);
    }

    @Test
    void buildsSummaryAllocationsByChain() {
        PortfolioSummary summary = portfolioService.getSummary(
                List.of("0xabc", "0xdef"),
                List.of("ethereum", "base"));

        assertThat(summary.totalUsd()).isEqualByComparingTo("6100.00");
        assertThat(summary.allocations()).hasSize(2);
        assertThat(summary.allocations().get(0).network()).isEqualTo("eth-mainnet");
    }

    @Test
    void buildsSummaryAllocationsByWallet() {
        PortfolioSummary summary = portfolioService.getSummary(
                List.of("0xabc", "0xdef"),
                List.of("ethereum", "base"));

        assertThat(summary.walletAllocations()).hasSize(2);
        assertThat(summary.walletAllocations().get(0).walletAddress()).isEqualTo("0xdef");
        assertThat(summary.walletAllocations().get(0).valueUsd()).isEqualByComparingTo("4000.00");
        assertThat(summary.walletAllocations().get(1).walletAddress()).isEqualTo("0xabc");
        assertThat(summary.walletAllocations().get(1).valueUsd()).isEqualByComparingTo("2100.00");
    }

    @Test
    void countsSolanaSpotAssetsWithoutLowercasingAddressesOrMints() {
        PortfolioService service = new PortfolioService(
                new SolanaProvider(),
                new ChainCatalogService(),
                new SimpleTtlCache(new PortfolioProperties()),
                new WrapperHeuristicLendingDetector(new PortfolioProperties()));

        var analysis = service.getAnalysis(
                List.of("H3cK6QhV1vN9A2bY8sLmP4rT7xZ5uWqE3dF9gJ2kL6mN"),
                List.of("solana"));

        assertThat(analysis.summary().totalUsd()).isEqualByComparingTo("187.50");
        assertThat(analysis.summary().allocations()).hasSize(1);
        assertThat(analysis.summary().allocations().get(0).network()).isEqualTo("solana-mainnet");
        assertThat(analysis.summary().allocations().get(0).displayName()).isEqualTo("Solana");
        assertThat(analysis.summary().walletAllocations().get(0).walletAddress())
                .isEqualTo("H3cK6QhV1vN9A2bY8sLmP4rT7xZ5uWqE3dF9gJ2kL6mN");
        assertThat(analysis.assets().get(0).assetId())
                .isEqualTo("solana-mainnet:JUPyiwrYJFskUPiHa7hkeR8VUtAeFoSYbKedZNsDvCN");
    }

    @Test
    void detectsWrapperTokenAsLendingPositionWithoutChangingSpotTotals() {
        var analysis = portfolioService.getAnalysis(
                List.of("0xabc", "0xdef"),
                List.of("ethereum", "base"));

        assertThat(analysis.summary().totalUsd()).isEqualByComparingTo("6100.00");
        assertThat(analysis.assets()).extracting(AssetBalance::assetId).contains("base-mainnet:0x123");
        assertThat(analysis.lendingPositions()).hasSize(1);
        assertThat(analysis.defiPositions()).hasSize(1);

        LendingPosition position = analysis.lendingPositions().get(0);
        assertThat(position.walletAddress()).isEqualTo("0xabc");
        assertThat(position.protocolKey()).isEqualTo("aave");
        assertThat(position.coverage().apiValue()).isEqualTo("partial");
        assertThat(position.detectionMode().apiValue()).isEqualTo("heuristic");
        assertThat(position.alreadyCountedInPortfolio()).isTrue();
        assertThat(position.sourceAssetId()).isEqualTo("base-mainnet:0x123");
        assertThat(analysis.lendingSummary().trackedPositions()).isEqualTo(1);
        assertThat(analysis.lendingSummary().visibleSupplyUsd()).isEqualByComparingTo("100.00");
        assertThat(analysis.lendingSummary().visibleDebtUsd()).isEqualByComparingTo("0.00");
        assertThat(analysis.lendingSummary().visibleNetUsd()).isEqualByComparingTo("100.00");
        assertThat(analysis.defiSummary().trackedPositions()).isEqualTo(1);
        assertThat(analysis.defiSummary().visibleSupplyUsd()).isEqualByComparingTo("100.00");
    }

    @Test
    void keepsPerWalletGranularityForDetectedWrapperPositions() {
        PortfolioProperties properties = new PortfolioProperties();
        properties.getLending().setWrapperTokens(List.of(wrapper("base-mainnet", "0xwrapper", "aave", "Aave", "0xusdc", "USDC", "USD Coin")));
        PortfolioService service = new PortfolioService(
                new PerWalletWrapperProvider(),
                new ChainCatalogService(),
                new SimpleTtlCache(properties),
                new WrapperHeuristicLendingDetector(properties));

        var analysis = service.getAnalysis(List.of("0xabc", "0xdef"), List.of("base"));

        assertThat(analysis.lendingPositions()).hasSize(2);
        assertThat(analysis.lendingPositions())
                .extracting(LendingPosition::walletAddress)
                .containsExactlyInAnyOrder("0xabc", "0xdef");
    }

    @Test
    void ignoresTokensThatAreNotPresentInWrapperRegistry() {
        PortfolioProperties properties = new PortfolioProperties();
        PortfolioService service = new PortfolioService(
                new StubProvider(),
                new ChainCatalogService(),
                new SimpleTtlCache(properties),
                new WrapperHeuristicLendingDetector(properties));

        var analysis = service.getAnalysis(List.of("0xabc", "0xdef"), List.of("ethereum", "base"));

        assertThat(analysis.lendingPositions()).isEmpty();
        assertThat(analysis.lendingSummary().trackedPositions()).isZero();
    }

    @Test
    void computesGrossAssetsLiabilitiesAndNetFromDefiProtocolRegistry() {
        PortfolioProperties properties = new PortfolioProperties();
        properties.getDefi().setProtocols(List.of(defiProtocol(
                "aave",
                "Aave",
                "base-mainnet",
                wrapperToken("0xausdc", "0xusdc", "USDC", "USD Coin", true),
                debtToken("0xdusdc", "0xusdc", "USDC", "USD Coin"))));

        PortfolioService service = new PortfolioService(
                new AaveSupplyAndDebtProvider(),
                new ChainCatalogService(),
                new SimpleTtlCache(properties),
                new WrapperHeuristicLendingDetector(properties));

        var analysis = service.getAnalysis(List.of("0xabc"), List.of("base"));

        assertThat(analysis.defiPositions()).hasSize(2);
        assertThat(analysis.lendingPositions()).hasSize(2);
        assertThat(analysis.summary().grossAssetUsd()).isEqualByComparingTo("100.00");
        assertThat(analysis.summary().grossLiabilityUsd()).isEqualByComparingTo("40.00");
        assertThat(analysis.summary().netUsd()).isEqualByComparingTo("60.00");
        assertThat(analysis.summary().totalUsd()).isEqualByComparingTo("60.00");
        assertThat(analysis.lendingSummary().visibleSupplyUsd()).isEqualByComparingTo("100.00");
        assertThat(analysis.lendingSummary().visibleDebtUsd()).isEqualByComparingTo("40.00");
        assertThat(analysis.lendingSummary().visibleNetUsd()).isEqualByComparingTo("60.00");
        assertThat(analysis.lendingPositions())
                .extracting(LendingPosition::positionSide)
                .containsExactlyInAnyOrder(
                        com.pnltracker.domain.LendingPositionSide.SUPPLY,
                        com.pnltracker.domain.LendingPositionSide.DEBT);
    }

    private static PortfolioProperties.WrapperTokenProperties wrapper(
            String network,
            String tokenAddress,
            String protocolKey,
            String protocolName,
            String underlyingTokenAddress,
            String underlyingSymbol,
            String underlyingName) {
        PortfolioProperties.WrapperTokenProperties wrapper = new PortfolioProperties.WrapperTokenProperties();
        wrapper.setNetwork(network);
        wrapper.setTokenAddress(tokenAddress);
        wrapper.setProtocolKey(protocolKey);
        wrapper.setProtocolName(protocolName);
        wrapper.setUnderlyingTokenAddress(underlyingTokenAddress);
        wrapper.setUnderlyingSymbol(underlyingSymbol);
        wrapper.setUnderlyingName(underlyingName);
        return wrapper;
    }

    private static PortfolioProperties.ProtocolProperties defiProtocol(
            String protocolKey,
            String protocolName,
            String network,
            PortfolioProperties.WrapperTokenProperties wrapperToken,
            PortfolioProperties.DebtTokenProperties debtToken) {
        PortfolioProperties.ProtocolNetworkProperties protocolNetwork = new PortfolioProperties.ProtocolNetworkProperties();
        protocolNetwork.setNetwork(network);
        protocolNetwork.setWrapperTokens(List.of(wrapperToken));
        protocolNetwork.setDebtTokens(List.of(debtToken));

        PortfolioProperties.ProtocolProperties protocol = new PortfolioProperties.ProtocolProperties();
        protocol.setProtocolKey(protocolKey);
        protocol.setProtocolName(protocolName);
        protocol.setNetworks(List.of(protocolNetwork));
        return protocol;
    }

    private static PortfolioProperties.WrapperTokenProperties wrapperToken(
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

    private static final class StubProvider implements AssetPortfolioProvider {

        @Override
        public ProviderFetchResult fetchAssetsWithDebug(List<String> addresses, List<String> networks) {
            return new ProviderFetchResult(List.of(
                    new ProviderAsset("0xabc", "eth-mainnet", null, "ETH", "Ethereum", 18, new BigDecimal("1.0"), new BigDecimal("2000"), true, null),
                    new ProviderAsset("0xdef", "eth-mainnet", null, "ETH", "Ethereum", 18, new BigDecimal("2.0"), new BigDecimal("2000"), true, null),
                    new ProviderAsset("0xabc", "base-mainnet", "0x123", "USDC", "USD Coin", 6, new BigDecimal("100.0"), new BigDecimal("1"), false, null),
                    new ProviderAsset("0xabc", "base-mainnet", "0xspam", "SPAM", "Spam", 18, new BigDecimal("0.1"), new BigDecimal("5"), false, null),
                    new ProviderAsset("0xabc", "base-mainnet", "0xbb", "BB", "Bubble Base", 18, new BigDecimal("100000"), new BigDecimal("6777667.89169339"), false, null)),
                    List.of(),
                    List.of());
        }

        @Override
        public String providerName() {
            return "stub";
        }
    }

    private static final class PerWalletWrapperProvider implements AssetPortfolioProvider {

        @Override
        public ProviderFetchResult fetchAssetsWithDebug(List<String> addresses, List<String> networks) {
            return new ProviderFetchResult(
                    List.of(
                            new ProviderAsset("0xabc", "base-mainnet", "0xwrapper", "aUSDC", "Aave USDC", 6, new BigDecimal("120.0"), BigDecimal.ONE, false, null),
                            new ProviderAsset("0xdef", "base-mainnet", "0xwrapper", "aUSDC", "Aave USDC", 6, new BigDecimal("75.0"), BigDecimal.ONE, false, null)),
                    List.of(),
                    List.of());
        }

        @Override
        public String providerName() {
            return "per-wallet-wrapper";
        }
    }

    private static final class AaveSupplyAndDebtProvider implements AssetPortfolioProvider {

        @Override
        public ProviderFetchResult fetchAssetsWithDebug(List<String> addresses, List<String> networks) {
            return new ProviderFetchResult(
                    List.of(
                            new ProviderAsset("0xabc", "base-mainnet", "0xausdc", "aUSDC", "Aave USDC", 6, new BigDecimal("100.0"), BigDecimal.ONE, false, null),
                            new ProviderAsset("0xabc", "base-mainnet", "0xdusdc", "variableDebtUSDC", "Variable Debt USDC", 6, new BigDecimal("40.0"), BigDecimal.ONE, false, null)),
                    List.of(),
                    List.of());
        }

        @Override
        public String providerName() {
            return "aave-supply-debt";
        }
    }

    private static final class SolanaProvider implements AssetPortfolioProvider {

        @Override
        public ProviderFetchResult fetchAssetsWithDebug(List<String> addresses, List<String> networks) {
            return new ProviderFetchResult(
                    List.of(new ProviderAsset(
                            "H3cK6QhV1vN9A2bY8sLmP4rT7xZ5uWqE3dF9gJ2kL6mN",
                            "solana-mainnet",
                            "JUPyiwrYJFskUPiHa7hkeR8VUtAeFoSYbKedZNsDvCN",
                            "JUP",
                            "Jupiter",
                            6,
                            new BigDecimal("150.0"),
                            new BigDecimal("1.25"),
                            false,
                            null)),
                    List.of(),
                    List.of());
        }

        @Override
        public String providerName() {
            return "solana";
        }
    }
}
