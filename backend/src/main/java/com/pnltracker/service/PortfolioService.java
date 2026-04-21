package com.pnltracker.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.pnltracker.defi.adapter.ProtocolPositionAdapter;
import com.pnltracker.defi.adapter.WrapperHeuristicDefiAdapter;
import com.pnltracker.defi.registry.ProtocolRegistry;
import com.pnltracker.defi.service.DefiDetectionResult;
import com.pnltracker.defi.service.DefiPositionService;
import com.pnltracker.defi.service.PortfolioDefiAggregationService;
import com.pnltracker.domain.AssetBalance;
import com.pnltracker.domain.ChainAllocation;
import com.pnltracker.domain.LendingPosition;
import com.pnltracker.domain.LendingPositionSummary;
import com.pnltracker.domain.ChainDefinition;
import com.pnltracker.domain.PortfolioAnalysis;
import com.pnltracker.domain.PortfolioSummary;
import com.pnltracker.domain.WalletAllocation;
import com.pnltracker.provider.AssetPortfolioProvider;
import com.pnltracker.provider.ProviderAsset;

@Service
public class PortfolioService {

    private static final BigDecimal MIN_VALUE_USD = new BigDecimal("1");
    private static final BigDecimal MAX_ERC20_PRICE_USD = new BigDecimal("1000000");
    private static final BigDecimal MAX_UNVERIFIED_ERC20_VALUE_USD = new BigDecimal("100000000");
    private static final Set<String> SUSPICIOUS_METADATA_PATTERNS = Set.of(
            "airdrop",
            "claim",
            "visit",
            "reward",
            "distribution",
            "http",
            "https",
            "www.",
            "t.me",
            "telegram",
            ".com",
            ".io",
            ".net");

    private final AssetPortfolioProvider provider;
    private final ChainCatalogService chainCatalogService;
    private final SimpleTtlCache cache;
    private final DefiPositionService defiPositionService;
    private final PortfolioDefiAggregationService portfolioDefiAggregationService;

    @Autowired
    public PortfolioService(
            AssetPortfolioProvider provider,
            ChainCatalogService chainCatalogService,
            SimpleTtlCache cache,
            DefiPositionService defiPositionService,
            PortfolioDefiAggregationService portfolioDefiAggregationService) {
        this.provider = provider;
        this.chainCatalogService = chainCatalogService;
        this.cache = cache;
        this.defiPositionService = defiPositionService;
        this.portfolioDefiAggregationService = portfolioDefiAggregationService;
    }

    public PortfolioService(
            AssetPortfolioProvider provider,
            ChainCatalogService chainCatalogService,
            SimpleTtlCache cache,
            LendingPositionDetector lendingPositionDetector) {
        this(
                provider,
                chainCatalogService,
                cache,
                new DefiPositionService(List.<ProtocolPositionAdapter>of(new WrapperHeuristicDefiAdapter(new ProtocolRegistry(extractProperties(lendingPositionDetector))))),
                new PortfolioDefiAggregationService());
    }

    private static com.pnltracker.config.PortfolioProperties extractProperties(LendingPositionDetector lendingPositionDetector) {
        if (lendingPositionDetector instanceof WrapperHeuristicLendingDetector wrapperDetector) {
            return wrapperDetector.propertiesView();
        }
        throw new IllegalArgumentException("Legacy constructor only supports WrapperHeuristicLendingDetector");
    }

    public PortfolioSummary getSummary(List<String> addresses, List<String> chains) {
        return getAnalysis(addresses, chains).summary();
    }

    public List<AssetBalance> getAssets(List<String> addresses, List<String> chains) {
        return getAnalysis(addresses, chains).assets();
    }

    public PortfolioAnalysis getAnalysis(List<String> addresses, List<String> chains) {
        List<String> normalizedAddresses = normalizeAddresses(addresses);
        if (normalizedAddresses.isEmpty()) {
            throw new IllegalArgumentException("At least one address is required");
        }
        List<ChainDefinition> requestedChains = chainCatalogService.resolve(chains);
        List<String> networks = requestedChains.stream().map(ChainDefinition::providerNetwork).toList();
        String key = "analysis:" + cacheKey(normalizedAddresses, networks) + ":" + provider.providerName();

        return cache.getOrCompute(key, () -> analyzeProviderAssets(provider.fetchAssets(normalizedAddresses, networks), normalizedAddresses, networks));
    }

    public PortfolioAnalysis analyzeProviderAssets(List<ProviderAsset> providerAssets) {
        return analyzeProviderAssets(providerAssets, List.of(), List.of());
    }

    public PortfolioAnalysis analyzeProviderAssets(
            List<ProviderAsset> providerAssets,
            List<String> addresses,
            List<String> networks) {
        DefiDetectionResult defiDetection = defiPositionService.detect(addresses, networks, providerAssets);
        List<LendingPosition> lendingPositions = defiDetection.lendingPositions();
        LendingPositionSummary lendingSummary = defiDetection.lendingSummary();
        Map<String, MutableAsset> byAsset = new LinkedHashMap<>();
        Map<String, List<ProviderAsset>> rawByAsset = new LinkedHashMap<>();
        for (ProviderAsset providerAsset : providerAssets) {
            String assetId = AssetIds.fromProviderAsset(providerAsset);
            byAsset.computeIfAbsent(assetId, ignored -> new MutableAsset(providerAsset))
                    .add(providerAsset.quantity(), providerAsset.priceUsd());
            rawByAsset.computeIfAbsent(assetId, ignored -> new java.util.ArrayList<>()).add(providerAsset);
        }

        List<AssetBalance> allAssets = byAsset.values().stream()
                .map(MutableAsset::toAssetBalance)
                .sorted(Comparator.comparing(AssetBalance::valueUsd).reversed())
                .toList();

        List<AssetBalance> visibleAssets = allAssets.stream()
                .filter(asset -> asset.priceUsd() != null)
                .filter(asset -> asset.valueUsd().compareTo(MIN_VALUE_USD) >= 0)
                .filter(asset -> !isSpamOrOutlier(asset))
                .toList();

        int hiddenAssets = allAssets.size() - visibleAssets.size();
        Set<String> visibleAssetIds = visibleAssets.stream()
                .map(AssetBalance::assetId)
                .collect(Collectors.toSet());
        Map<String, BigDecimal> valueByChain = new LinkedHashMap<>();
        Map<String, BigDecimal> valueByWallet = new LinkedHashMap<>();
        BigDecimal totalUsd = BigDecimal.ZERO;

        for (AssetBalance asset : visibleAssets) {
            totalUsd = totalUsd.add(asset.valueUsd());
            valueByChain.merge(asset.network(), asset.valueUsd(), BigDecimal::add);
        }

        for (Map.Entry<String, List<ProviderAsset>> entry : rawByAsset.entrySet()) {
            if (!visibleAssetIds.contains(entry.getKey())) {
                continue;
            }

            for (ProviderAsset providerAsset : entry.getValue()) {
                if (providerAsset.priceUsd() == null) {
                    continue;
                }

                BigDecimal valueUsd = providerAsset.quantity()
                        .multiply(providerAsset.priceUsd());
                valueByWallet.merge(providerAsset.walletAddress().toLowerCase(Locale.ROOT), valueUsd, BigDecimal::add);
            }
        }

        List<ChainAllocation> allocations = valueByChain.entrySet().stream()
                .map(entry -> new ChainAllocation(
                        entry.getKey(),
                        chainCatalogService.displayNameForNetwork(entry.getKey()),
                        entry.getValue().setScale(2, RoundingMode.HALF_UP)))
                .sorted(Comparator.comparing(ChainAllocation::valueUsd).reversed())
                .toList();

        List<WalletAllocation> walletAllocations = valueByWallet.entrySet().stream()
                .map(entry -> new WalletAllocation(
                        entry.getKey(),
                        entry.getValue().setScale(2, RoundingMode.HALF_UP)))
                .sorted(Comparator.comparing(WalletAllocation::valueUsd).reversed())
                .toList();

        PortfolioSummary spotSummary = new PortfolioSummary(
                totalUsd.setScale(2, RoundingMode.HALF_UP),
                visibleAssets.size(),
                hiddenAssets,
                allocations,
                walletAllocations);
        PortfolioSummary summary = portfolioDefiAggregationService.applyDefiAdjustments(spotSummary, defiDetection.defiPositions());
        return new PortfolioAnalysis(
                visibleAssets,
                summary,
                lendingPositions,
                lendingSummary,
                defiDetection.defiPositions(),
                defiDetection.defiSummary());
    }

    private boolean isSpamOrOutlier(AssetBalance asset) {
        if (asset.nativeToken()) {
            return false;
        }
        if (isBlank(asset.symbol()) || isBlank(asset.name())) {
            return true;
        }

        String metadata = (asset.symbol() + " " + asset.name()).toLowerCase(Locale.ROOT);
        boolean suspiciousMetadata = SUSPICIOUS_METADATA_PATTERNS.stream().anyMatch(metadata::contains);
        boolean absurdUnitPrice = asset.priceUsd() != null && asset.priceUsd().compareTo(MAX_ERC20_PRICE_USD) > 0;
        boolean oversizedUnverifiedValue = asset.logoUrl() == null
                && asset.valueUsd().compareTo(MAX_UNVERIFIED_ERC20_VALUE_USD) > 0;

        return suspiciousMetadata || absurdUnitPrice || oversizedUnverifiedValue;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private List<String> normalizeAddresses(List<String> addresses) {
        return addresses.stream()
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(String::toLowerCase)
                .distinct()
                .toList();
    }

    private String cacheKey(List<String> addresses, List<String> networks) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String payload = String.join(",", addresses) + "|" + String.join(",", networks);
            return HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 not available", exception);
        }
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
        private BigDecimal quantity = BigDecimal.ZERO;
        private BigDecimal priceUsd = BigDecimal.ZERO;

        private MutableAsset(ProviderAsset providerAsset) {
            this.assetId = providerAsset.network() + ":" +
                    (providerAsset.tokenAddress() == null ? "native" : providerAsset.tokenAddress().toLowerCase());
            this.network = providerAsset.network();
            this.tokenAddress = providerAsset.tokenAddress();
            this.symbol = providerAsset.symbol();
            this.name = providerAsset.name();
            this.decimals = providerAsset.decimals();
            this.nativeToken = providerAsset.nativeToken();
            this.logoUrl = providerAsset.logoUrl();
        }

        private MutableAsset add(BigDecimal extraQuantity, BigDecimal latestPriceUsd) {
            quantity = quantity.add(extraQuantity);
            if (latestPriceUsd != null) {
                priceUsd = latestPriceUsd;
            }
            return this;
        }

        private AssetBalance toAssetBalance() {
            BigDecimal valueUsd = quantity.multiply(priceUsd).setScale(2, RoundingMode.HALF_UP);
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
