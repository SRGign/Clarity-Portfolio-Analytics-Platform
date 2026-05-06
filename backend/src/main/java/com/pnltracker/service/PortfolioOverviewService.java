package com.pnltracker.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.springframework.stereotype.Service;

import com.pnltracker.defi.model.DefiPosition;
import com.pnltracker.defi.model.DefiPositionSummary;
import com.pnltracker.domain.AssetBalance;
import com.pnltracker.domain.ChainAllocation;
import com.pnltracker.domain.LendingPosition;
import com.pnltracker.domain.LendingPositionSummary;
import com.pnltracker.domain.PortfolioAnalysis;
import com.pnltracker.domain.PortfolioSummary;
import com.pnltracker.domain.WalletAllocation;
import com.pnltracker.hyperliquid.HyperliquidPortfolioService;

@Service
public class PortfolioOverviewService {

    private final PortfolioService portfolioService;
    private final HyperliquidPortfolioService hyperliquidPortfolioService;
    private final SimpleTtlCache cache;

    public PortfolioOverviewService(
            PortfolioService portfolioService,
            HyperliquidPortfolioService hyperliquidPortfolioService,
            SimpleTtlCache cache) {
        this.portfolioService = portfolioService;
        this.hyperliquidPortfolioService = hyperliquidPortfolioService;
        this.cache = cache;
    }

    public PortfolioAnalysis getOverview(List<String> addresses, List<String> chains) {
        List<String> normalizedAddresses = addresses.stream()
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(PortfolioOverviewService::normalizeWalletKey)
                .distinct()
                .toList();
        if (normalizedAddresses.isEmpty()) {
            throw new IllegalArgumentException("At least one address is required");
        }

        String key = "overview:" + cacheKey(normalizedAddresses, chains == null ? List.of() : chains);
        return cache.getOrCompute(key, () -> buildOverview(normalizedAddresses, chains));
    }

    private PortfolioAnalysis buildOverview(List<String> addresses, List<String> chains) {
        CompletableFuture<PortfolioAnalysis> evmFuture =
                CompletableFuture.supplyAsync(() -> portfolioService.getAnalysis(addresses, chains));
        CompletableFuture<PortfolioAnalysis> hyperliquidFuture =
                CompletableFuture.supplyAsync(() -> hyperliquidPortfolioService.getAnalysis(evmAddresses(addresses)));

        PortfolioAnalysis evm = evmFuture.join();
        PortfolioAnalysis hyperliquid = hyperliquidFuture.join();

        List<AssetBalance> assets = new ArrayList<>(evm.assets());
        assets.addAll(hyperliquid.assets());
        assets = assets.stream()
                .sorted(Comparator.comparing(AssetBalance::valueUsd).reversed())
                .toList();

        List<LendingPosition> lendingPositions = new ArrayList<>(evm.lendingPositions());
        lendingPositions.addAll(hyperliquid.lendingPositions());
        lendingPositions = List.copyOf(lendingPositions);

        List<DefiPosition> defiPositions = new ArrayList<>(evm.defiPositions());
        defiPositions.addAll(hyperliquid.defiPositions());
        defiPositions = List.copyOf(defiPositions);

        Map<String, ChainAllocation> allocations = new LinkedHashMap<>();
        evm.summary().allocations().forEach(allocation -> allocations.put(allocation.network(), allocation));
        hyperliquid.summary().allocations().forEach(allocation -> allocations.merge(
                allocation.network(),
                allocation,
                (left, right) -> new ChainAllocation(
                        left.network(),
                        left.displayName(),
                        left.valueUsd().add(right.valueUsd()).setScale(2, RoundingMode.HALF_UP))));

        Map<String, WalletAllocation> walletAllocations = new LinkedHashMap<>();
        evm.summary().walletAllocations().forEach(allocation -> walletAllocations.put(allocation.walletAddress(), allocation));
        hyperliquid.summary().walletAllocations().forEach(allocation -> walletAllocations.merge(
                allocation.walletAddress(),
                allocation,
                (left, right) -> new WalletAllocation(
                        left.walletAddress(),
                        left.valueUsd().add(right.valueUsd()).setScale(2, RoundingMode.HALF_UP))));

        BigDecimal grossAssetUsd = evm.summary().grossAssetUsd()
                .add(hyperliquid.summary().grossAssetUsd())
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal grossLiabilityUsd = evm.summary().grossLiabilityUsd()
                .add(hyperliquid.summary().grossLiabilityUsd())
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal netUsd = evm.summary().netUsd()
                .add(hyperliquid.summary().netUsd())
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal totalUsd = evm.summary().totalUsd()
                .add(hyperliquid.summary().totalUsd())
                .setScale(2, RoundingMode.HALF_UP);

        List<ChainAllocation> allocationRows = allocations.values().stream()
                .sorted(Comparator.comparing(ChainAllocation::valueUsd).reversed())
                .toList();

        List<WalletAllocation> walletAllocationRows = walletAllocations.values().stream()
                .sorted(Comparator.comparing(WalletAllocation::valueUsd).reversed())
                .toList();

        return new PortfolioAnalysis(
                assets,
                new PortfolioSummary(
                        totalUsd,
                        grossAssetUsd,
                        grossLiabilityUsd,
                        netUsd,
                        assets.size(),
                        evm.summary().hiddenAssets() + hyperliquid.summary().hiddenAssets(),
                        allocationRows,
                        walletAllocationRows),
                lendingPositions,
                LendingPositionSummary.fromPositions(lendingPositions),
                defiPositions,
                DefiPositionSummary.fromPositions(defiPositions));
    }

    private String cacheKey(List<String> addresses, List<String> chains) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String payload = String.join(",", addresses) + "|" + String.join(",", chains);
            return HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 not available", exception);
        }
    }

    private static List<String> evmAddresses(List<String> addresses) {
        return addresses.stream()
                .filter(PortfolioOverviewService::isEvmAddress)
                .toList();
    }

    private static String normalizeWalletKey(String address) {
        String trimmed = address == null ? "" : address.trim();
        return isEvmAddress(trimmed) ? trimmed.toLowerCase(Locale.ROOT) : trimmed;
    }

    private static boolean isEvmAddress(String value) {
        return value.matches("(?i)^0x[0-9a-f]{40}$");
    }
}
