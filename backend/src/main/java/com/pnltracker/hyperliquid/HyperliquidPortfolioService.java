package com.pnltracker.hyperliquid;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.pnltracker.domain.AssetBalance;
import com.pnltracker.domain.ChainAllocation;
import com.pnltracker.domain.LendingPositionSummary;
import com.pnltracker.domain.PortfolioAnalysis;
import com.pnltracker.domain.PortfolioSummary;
import com.pnltracker.domain.WalletAllocation;

@Service
public class HyperliquidPortfolioService {

    private static final Logger log = LoggerFactory.getLogger(HyperliquidPortfolioService.class);
    private static final String NETWORK = "hyperliquid";
    private static final BigDecimal MIN_VISIBLE_VALUE_USD = new BigDecimal("1");

    private final HyperliquidService hyperliquidService;

    public HyperliquidPortfolioService(HyperliquidService hyperliquidService) {
        this.hyperliquidService = hyperliquidService;
    }

    public PortfolioAnalysis getAnalysis(List<String> addresses) {
        List<String> users = addresses.stream()
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(String::toLowerCase)
                .distinct()
                .toList();

        if (users.isEmpty()) {
            return emptyAnalysis();
        }

        List<UserPortfolio> portfolios = users.stream()
                .map(user -> CompletableFuture.supplyAsync(() -> fetchUserPortfolio(user)))
                .toList()
                .stream()
                .map(CompletableFuture::join)
                .filter(portfolio -> portfolio != null)
                .toList();

        if (portfolios.isEmpty()) {
            return emptyAnalysis();
        }

        Map<String, MutableAsset> byAsset = new LinkedHashMap<>();
        BigDecimal totalUsd = BigDecimal.ZERO;
        int hiddenAssets = 0;

        for (UserPortfolio portfolio : portfolios) {
            totalUsd = totalUsd.add(portfolio.totalUsd());
            hiddenAssets += portfolio.hiddenAssets();
            for (AssetBalance asset : portfolio.assets()) {
                byAsset.computeIfAbsent(asset.assetId(), ignored -> new MutableAsset(asset))
                        .add(asset.quantity(), asset.priceUsd());
            }
        }

        List<AssetBalance> assets = byAsset.values().stream()
                .map(MutableAsset::toAssetBalance)
                .sorted(Comparator.comparing(AssetBalance::valueUsd).reversed())
                .toList();

        List<ChainAllocation> allocations = totalUsd.compareTo(BigDecimal.ZERO) > 0
                ? List.of(new ChainAllocation(
                        NETWORK,
                        "Hyperliquid",
                        totalUsd.setScale(2, RoundingMode.HALF_UP)))
                : List.of();

        List<WalletAllocation> walletAllocations = portfolios.stream()
                .map(portfolio -> new WalletAllocation(
                        portfolio.user(),
                        portfolio.totalUsd().setScale(2, RoundingMode.HALF_UP)))
                .sorted(Comparator.comparing(WalletAllocation::valueUsd).reversed())
                .toList();

        return new PortfolioAnalysis(
                assets,
                new PortfolioSummary(
                        totalUsd.setScale(2, RoundingMode.HALF_UP),
                        assets.size(),
                        hiddenAssets,
                        allocations,
                        walletAllocations),
                List.of(),
                LendingPositionSummary.empty());
    }

    private UserPortfolio fetchUserPortfolio(String user) {
        try {
            HyperliquidSummary summary = hyperliquidService.fetchReport(user).summary();
            return toUserPortfolio(user, summary);
        } catch (RuntimeException exception) {
            log.debug("Skipping Hyperliquid for {}: {}", user, exception.getMessage());
            return null;
        }
    }

    private UserPortfolio toUserPortfolio(String user, HyperliquidSummary summary) {
        List<AssetBalance> visibleAssets = new ArrayList<>();
        int hiddenAssets = 0;
        BigDecimal componentTotal = BigDecimal.ZERO;

        for (HyperliquidSpotBalance balance : summary.spotBalances()) {
            if (!isVisible(balance.valueUsd(), balance.priceUsd())) {
                hiddenAssets++;
                continue;
            }
            visibleAssets.add(new AssetBalance(
                    NETWORK + ":spot:" + balance.coin().toLowerCase(Locale.ROOT),
                    NETWORK,
                    "spot:" + balance.coin().toLowerCase(Locale.ROOT),
                    balance.coin(),
                    "Hyperliquid Spot " + balance.coin(),
                    0,
                    balance.total(),
                    balance.priceUsd(),
                    balance.valueUsd().setScale(2, RoundingMode.HALF_UP),
                    false,
                    null));
            componentTotal = componentTotal.add(balance.valueUsd());
        }

        BigDecimal perpAccountValue = summary.perpAccountValue().setScale(2, RoundingMode.HALF_UP);
        if (perpAccountValue.compareTo(BigDecimal.ZERO) > 0) {
            if (perpAccountValue.compareTo(MIN_VISIBLE_VALUE_USD) >= 0) {
                visibleAssets.add(syntheticDollarAsset(
                        NETWORK + ":perp-account",
                        "perp-account",
                        "PERP",
                        "Hyperliquid Perp Account",
                        perpAccountValue));
                componentTotal = componentTotal.add(perpAccountValue);
            } else {
                hiddenAssets++;
            }
        }

        for (HyperliquidVaultEquity vault : summary.vaultEquities()) {
            BigDecimal equity = vault.equity().setScale(2, RoundingMode.HALF_UP);
            if (equity.compareTo(MIN_VISIBLE_VALUE_USD) < 0) {
                hiddenAssets++;
                continue;
            }
            visibleAssets.add(syntheticDollarAsset(
                    NETWORK + ":vault:" + vault.vaultAddress().toLowerCase(Locale.ROOT),
                    vault.vaultAddress(),
                    "VAULT",
                    "Hyperliquid Vault",
                    equity));
            componentTotal = componentTotal.add(equity);
        }

        BigDecimal totalUsd = summary.latestPortfolioAccountValue().setScale(2, RoundingMode.HALF_UP);
        BigDecimal residual = totalUsd.subtract(componentTotal).setScale(2, RoundingMode.HALF_UP);
        if (residual.abs().compareTo(MIN_VISIBLE_VALUE_USD) >= 0) {
            visibleAssets.add(syntheticDollarAsset(
                    NETWORK + ":cash",
                    "cash-balance",
                    residual.signum() < 0 ? "HL-LIABILITY" : "HL-CASH",
                    residual.signum() < 0 ? "Hyperliquid Liability" : "Hyperliquid Cash",
                    residual));
        }

        boolean hasMeaningfulData = totalUsd.compareTo(BigDecimal.ZERO) > 0
                || !visibleAssets.isEmpty()
                || hiddenAssets > 0;
        return hasMeaningfulData ? new UserPortfolio(user, totalUsd, visibleAssets, hiddenAssets) : null;
    }

    private boolean isVisible(BigDecimal valueUsd, BigDecimal priceUsd) {
        return valueUsd != null
                && priceUsd != null
                && valueUsd.compareTo(MIN_VISIBLE_VALUE_USD) >= 0;
    }

    private AssetBalance syntheticDollarAsset(
            String assetId,
            String tokenAddress,
            String symbol,
            String name,
            BigDecimal valueUsd) {
        BigDecimal scaledValue = valueUsd.setScale(2, RoundingMode.HALF_UP);
        return new AssetBalance(
                assetId,
                NETWORK,
                tokenAddress,
                symbol,
                name,
                0,
                scaledValue,
                BigDecimal.ONE,
                scaledValue,
                false,
                null);
    }

    private PortfolioAnalysis emptyAnalysis() {
        return new PortfolioAnalysis(
                List.of(),
                new PortfolioSummary(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP), 0, 0, List.of(), List.of()),
                List.of(),
                LendingPositionSummary.empty());
    }

    private record UserPortfolio(
            String user,
            BigDecimal totalUsd,
            List<AssetBalance> assets,
            int hiddenAssets) {
    }

    private static final class MutableAsset {
        private final String assetId;
        private final String network;
        private final String tokenAddress;
        private final String symbol;
        private final String name;
        private final int decimals;
        private final boolean nativeToken;
        private final String logoUrl;
        private BigDecimal quantity;
        private BigDecimal priceUsd;

        private MutableAsset(AssetBalance asset) {
            this.assetId = asset.assetId();
            this.network = asset.network();
            this.tokenAddress = asset.tokenAddress();
            this.symbol = asset.symbol();
            this.name = asset.name();
            this.decimals = asset.decimals();
            this.nativeToken = asset.nativeToken();
            this.logoUrl = asset.logoUrl();
            this.quantity = BigDecimal.ZERO;
            this.priceUsd = asset.priceUsd();
        }

        private MutableAsset add(BigDecimal extraQuantity, BigDecimal latestPriceUsd) {
            quantity = quantity.add(extraQuantity);
            if (latestPriceUsd != null) {
                priceUsd = latestPriceUsd;
            }
            return this;
        }

        private AssetBalance toAssetBalance() {
            BigDecimal valueUsd = priceUsd == null
                    ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                    : quantity.multiply(priceUsd).setScale(2, RoundingMode.HALF_UP);
            return new AssetBalance(
                    assetId,
                    network,
                    tokenAddress,
                    symbol,
                    name,
                    decimals,
                    quantity,
                    priceUsd,
                    valueUsd,
                    nativeToken,
                    logoUrl);
        }
    }
}
