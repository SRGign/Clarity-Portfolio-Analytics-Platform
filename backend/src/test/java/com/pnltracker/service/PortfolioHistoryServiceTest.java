package com.pnltracker.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.pnltracker.domain.ChainDefinition;

class PortfolioHistoryServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-03-28T12:00:00Z"), ZoneOffset.UTC);
    private static final String EVM_ADDRESS = "0x1111111111111111111111111111111111111111";
    private static final String SOLANA_ADDRESS = "So11111111111111111111111111111111111111112";

    private InMemoryHistoryRepository repository;

    @BeforeEach
    void setUp() {
        repository = new InMemoryHistoryRepository();
    }

    @Test
    void persistsGoldRushHistoryAndReusesPersistedSnapshots() {
        PortfolioHistoryService service = new PortfolioHistoryService(
                new ChainCatalogService(),
                repository,
                request -> new PortfolioHistoryFetchResult("goldrush", values(request.requiredDates(), "100.00", "110.00"), Set.of()),
                FIXED_CLOCK);

        PortfolioHistoryResult first = service.getHistory(
                List.of(EVM_ADDRESS),
                List.of("ethereum"),
                PortfolioHistoryPeriod.H24);

        assertThat(first.points()).hasSize(2);
        assertThat(first.points()).allMatch(point -> !point.persisted());
        assertThat(first.points()).extracting(PortfolioHistoryResultPoint::source).containsOnly("goldrush");
        assertThat(first.points().get(0).totalUsd()).isEqualByComparingTo("100.00");
        assertThat(first.points().get(1).totalUsd()).isEqualByComparingTo("110.00");

        PortfolioHistoryResult second = service.getHistory(
                List.of(EVM_ADDRESS),
                List.of("ethereum"),
                PortfolioHistoryPeriod.H24);

        assertThat(second.points()).hasSize(2);
        assertThat(second.points()).allMatch(PortfolioHistoryResultPoint::persisted);
        assertThat(repository.snapshots()).hasSize(2);
    }

    @Test
    void marksHistoryPartialWhenProviderCannotFillRequestedChain() {
        PortfolioHistoryService service = new PortfolioHistoryService(
                new ChainCatalogService(),
                repository,
                request -> new PortfolioHistoryFetchResult("goldrush", values(request.requiredDates(), "80.00", "90.00"), Set.of("linea")),
                FIXED_CLOCK);

        PortfolioHistoryResult result = service.getHistory(
                List.of(EVM_ADDRESS),
                List.of("linea"),
                PortfolioHistoryPeriod.H24);

        assertThat(result.partial()).isTrue();
        assertThat(result.missingChains()).containsExactly("linea");
        assertThat(repository.snapshots()).allMatch(PortfolioHistorySnapshot::partial);
    }

    @Test
    void ignoresNonGoldRushSnapshotsAlreadyInStorage() {
        PortfolioHistoryService service = new PortfolioHistoryService(
                new ChainCatalogService(),
                repository,
                request -> new PortfolioHistoryFetchResult("goldrush", values(request.requiredDates(), "100.00", "110.00"), Set.of()),
                FIXED_CLOCK);

        PortfolioHistoryService legacyWriter = new PortfolioHistoryService(
                new ChainCatalogService(),
                repository,
                request -> new PortfolioHistoryFetchResult("hyperliquid", values(request.requiredDates(), "999.00", "999.00"), Set.of()),
                FIXED_CLOCK);
        legacyWriter.getHistory(List.of(EVM_ADDRESS), List.of("ethereum"), PortfolioHistoryPeriod.H24);

        PortfolioHistoryResult result = service.getHistory(
                List.of(EVM_ADDRESS),
                List.of("ethereum"),
                PortfolioHistoryPeriod.H24);

        assertThat(result.points()).hasSize(2);
        assertThat(result.points()).extracting(PortfolioHistoryResultPoint::source).containsOnly("goldrush");
        assertThat(result.points().get(0).totalUsd()).isEqualByComparingTo("100.00");
        assertThat(result.points().get(1).totalUsd()).isEqualByComparingTo("110.00");
    }

    @Test
    void excludesSolanaWalletsAndChainsFromGoldRushHistoryScope() {
        AtomicReference<PortfolioHistoryFetchRequest> capturedRequest = new AtomicReference<>();
        PortfolioHistoryService service = new PortfolioHistoryService(
                new ChainCatalogService(),
                repository,
                request -> {
                    capturedRequest.set(request);
                    return new PortfolioHistoryFetchResult("goldrush", values(request.requiredDates(), "100.00", "110.00"), Set.of());
                },
                FIXED_CLOCK);

        PortfolioHistoryResult mixedScope = service.getHistory(
                List.of(EVM_ADDRESS, SOLANA_ADDRESS),
                List.of("ethereum", "solana"),
                PortfolioHistoryPeriod.H24);
        PortfolioHistoryResult evmScope = service.getHistory(
                List.of(EVM_ADDRESS),
                List.of("ethereum"),
                PortfolioHistoryPeriod.H24);

        assertThat(capturedRequest.get().addresses()).containsExactly(EVM_ADDRESS);
        assertThat(capturedRequest.get().chains()).extracting(ChainDefinition::id).containsExactly("ethereum");
        assertThat(mixedScope.scopeHash()).isEqualTo(evmScope.scopeHash());
        assertThat(evmScope.points()).allMatch(PortfolioHistoryResultPoint::persisted);
        assertThat(repository.snapshots()).hasSize(2);
    }

    @Test
    void returnsEmptyHistoryWhenRequestHasNoEvmHistoryScope() {
        AtomicBoolean providerCalled = new AtomicBoolean(false);
        PortfolioHistoryService service = new PortfolioHistoryService(
                new ChainCatalogService(),
                repository,
                request -> {
                    providerCalled.set(true);
                    return new PortfolioHistoryFetchResult("goldrush", values(request.requiredDates(), "100.00", "110.00"), Set.of());
                },
                FIXED_CLOCK);

        PortfolioHistoryResult result = service.getHistory(
                List.of(SOLANA_ADDRESS),
                List.of("solana"),
                PortfolioHistoryPeriod.H24);

        assertThat(result.points()).isEmpty();
        assertThat(result.partial()).isFalse();
        assertThat(providerCalled).isFalse();
        assertThat(repository.snapshots()).isEmpty();
    }

    private Map<LocalDate, BigDecimal> values(Set<LocalDate> requiredDates, String first, String second) {
        List<LocalDate> orderedDates = requiredDates.stream().sorted().toList();
        Map<LocalDate, BigDecimal> values = new LinkedHashMap<>();
        values.put(orderedDates.get(0), new BigDecimal(first));
        values.put(orderedDates.get(1), new BigDecimal(second));
        return values;
    }

    private static final class InMemoryHistoryRepository implements PortfolioHistoryRepository {
        private final Map<String, PortfolioHistorySnapshot> snapshots = new LinkedHashMap<>();

        @Override
        public List<PortfolioHistorySnapshot> findSnapshots(String scopeHash, LocalDate fromDate, LocalDate toDate) {
            return snapshots.values().stream()
                    .filter(snapshot -> snapshot.scopeHash().equals(scopeHash))
                    .filter(snapshot -> !snapshot.localDate().isBefore(fromDate))
                    .filter(snapshot -> !snapshot.localDate().isAfter(toDate))
                    .sorted(java.util.Comparator.comparing(PortfolioHistorySnapshot::localDate))
                    .toList();
        }

        @Override
        public void upsertSnapshots(List<PortfolioHistorySnapshot> snapshots) {
            for (PortfolioHistorySnapshot snapshot : snapshots) {
                this.snapshots.put(snapshot.scopeHash() + ":" + snapshot.localDate(), snapshot);
            }
        }

        List<PortfolioHistorySnapshot> snapshots() {
            return List.copyOf(snapshots.values());
        }
    }
}
