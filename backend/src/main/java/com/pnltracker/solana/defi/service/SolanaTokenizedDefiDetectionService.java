package com.pnltracker.solana.defi.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.pnltracker.goldrush.GoldRushSolanaBalancesClient;
import com.pnltracker.goldrush.GoldRushSolanaBalancesClient.GoldRushSolanaBalanceItem;
import com.pnltracker.solana.defi.model.SolanaTokenizedDefiAssetDefinition;
import com.pnltracker.solana.defi.model.SolanaTokenizedDefiPosition;
import com.pnltracker.solana.defi.repository.SolanaDefiAssetRegistryRepository;
import com.pnltracker.solana.defi.repository.SolanaDefiPositionSnapshotRepository;

@Service
public class SolanaTokenizedDefiDetectionService {

    private static final Logger log = LoggerFactory.getLogger(SolanaTokenizedDefiDetectionService.class);

    private final GoldRushSolanaBalancesClient goldRushClient;
    private final SolanaDefiAssetRegistryRepository registryRepository;
    private final SolanaDefiPositionSnapshotRepository snapshotRepository;
    private final SolanaTokenizedDefiFallbackRegistry fallbackRegistry;
    private final Clock clock;

    @Autowired
    public SolanaTokenizedDefiDetectionService(
            GoldRushSolanaBalancesClient goldRushClient,
            SolanaDefiAssetRegistryRepository registryRepository,
            SolanaDefiPositionSnapshotRepository snapshotRepository,
            SolanaTokenizedDefiFallbackRegistry fallbackRegistry) {
        this(goldRushClient, registryRepository, snapshotRepository, fallbackRegistry, Clock.systemUTC());
    }

    SolanaTokenizedDefiDetectionService(
            GoldRushSolanaBalancesClient goldRushClient,
            SolanaDefiAssetRegistryRepository registryRepository,
            SolanaDefiPositionSnapshotRepository snapshotRepository,
            SolanaTokenizedDefiFallbackRegistry fallbackRegistry,
            Clock clock) {
        this.goldRushClient = goldRushClient;
        this.registryRepository = registryRepository;
        this.snapshotRepository = snapshotRepository;
        this.fallbackRegistry = fallbackRegistry;
        this.clock = clock;
    }

    public List<SolanaTokenizedDefiPosition> detect(String walletAddress) {
        String normalizedWallet = normalizeWallet(walletAddress);
        if (normalizedWallet == null) {
            throw new IllegalArgumentException("Wallet address is required");
        }

        Map<String, SolanaTokenizedDefiAssetDefinition> definitionsByMint = definitionsByMint();
        Instant observedAt = Instant.now(clock);
        List<SolanaTokenizedDefiPosition> positions = goldRushClient.fetchBalances(normalizedWallet).stream()
                .map(item -> toPosition(normalizedWallet, item, definitionsByMint, observedAt))
                .filter(Objects::nonNull)
                .toList();

        for (SolanaTokenizedDefiPosition position : positions) {
            try {
                snapshotRepository.saveSnapshot(position);
            } catch (RuntimeException exception) {
                log.warn("Failed to save Solana DeFi position snapshot wallet={} mint={}: {}",
                        position.walletAddress(),
                        position.mintAddress(),
                        exception.getMessage());
            }
        }

        log.info(
                "Solana tokenized DeFi detection wallet={} positions={} totalValueUsd={}",
                normalizedWallet,
                positions.size(),
                totalValueUsd(positions));
        return positions;
    }

    private SolanaTokenizedDefiPosition toPosition(
            String walletAddress,
            GoldRushSolanaBalanceItem item,
            Map<String, SolanaTokenizedDefiAssetDefinition> definitionsByMint,
            Instant observedAt) {
        if (item == null || item.mintAddress() == null || item.mintAddress().isBlank()) {
            return null;
        }
        if (item.quantity() == null || item.quantity().compareTo(BigDecimal.ZERO) <= 0) {
            log.debug("Ignoring zero Solana token balance wallet={} mint={}", walletAddress, item.mintAddress());
            return null;
        }

        SolanaTokenizedDefiAssetDefinition definition = definitionsByMint.get(item.mintAddress().trim());
        if (definition == null) {
            log.debug("Ignoring non-registered Solana tokenized DeFi mint wallet={} mint={}", walletAddress, item.mintAddress());
            return null;
        }

        return new SolanaTokenizedDefiPosition(
                walletAddress,
                definition.mintAddress(),
                definition.symbol(),
                definition.protocolKey(),
                definition.protocolName(),
                definition.positionType(),
                item.quantity(),
                item.priceUsd(),
                item.valueUsd(),
                definition.source(),
                definition.confidence(),
                definition.alreadyCountedInSpotTotals(),
                observedAt,
                item.rawPayloadJson());
    }

    private Map<String, SolanaTokenizedDefiAssetDefinition> definitionsByMint() {
        List<SolanaTokenizedDefiAssetDefinition> definitions;
        try {
            definitions = registryRepository.findEnabledDefinitions();
        } catch (RuntimeException exception) {
            log.warn("Solana DeFi registry lookup failed; using fallback registry: {}", exception.getMessage());
            definitions = List.of();
        }

        if (definitions == null || definitions.isEmpty()) {
            definitions = fallbackRegistry.enabledDefinitions();
        }

        Map<String, SolanaTokenizedDefiAssetDefinition> byMint = new LinkedHashMap<>();
        for (SolanaTokenizedDefiAssetDefinition definition : definitions) {
            if (definition.enabled() && definition.mintAddress() != null && !definition.mintAddress().isBlank()) {
                byMint.putIfAbsent(definition.mintAddress().trim(), definition);
            }
        }
        return byMint;
    }

    private BigDecimal totalValueUsd(List<SolanaTokenizedDefiPosition> positions) {
        return positions.stream()
                .map(SolanaTokenizedDefiPosition::valueUsd)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private String normalizeWallet(String walletAddress) {
        if (walletAddress == null || walletAddress.isBlank()) {
            return null;
        }
        return walletAddress.trim();
    }
}
