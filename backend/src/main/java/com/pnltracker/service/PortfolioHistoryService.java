package com.pnltracker.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.pnltracker.domain.ChainDefinition;

@Service
public class PortfolioHistoryService {

    private static final String HYPERLIQUID_SCOPE = "hyperliquid";

    private final ChainCatalogService chainCatalogService;
    private final PortfolioHistoryRepository historyRepository;
    private final List<PortfolioHistoryProvider> historyProviders;
    private final Clock clock;

    @Autowired
    public PortfolioHistoryService(
            ChainCatalogService chainCatalogService,
            PortfolioHistoryRepository historyRepository,
            List<PortfolioHistoryProvider> historyProviders) {
        this(chainCatalogService, historyRepository, historyProviders, Clock.systemUTC());
    }

    PortfolioHistoryService(
            ChainCatalogService chainCatalogService,
            PortfolioHistoryRepository historyRepository,
            List<PortfolioHistoryProvider> historyProviders,
            Clock clock) {
        this.chainCatalogService = chainCatalogService;
        this.historyRepository = historyRepository;
        this.historyProviders = List.copyOf(historyProviders);
        this.clock = clock;
    }

    public PortfolioHistoryResult getHistory(List<String> addresses, List<String> chains, PortfolioHistoryPeriod period) {
        Scope scope = normalizeScope(addresses, chains);
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        LocalDate fromDate = today.minusDays(period.lookbackDays());
        Set<LocalDate> requiredDates = requiredDates(fromDate, today);

        List<PortfolioHistorySnapshot> storedSnapshots = historyRepository.findSnapshots(scope.scopeHash(), fromDate, today);
        Map<LocalDate, PortfolioHistorySnapshot> storedByDate = new LinkedHashMap<>();
        storedSnapshots.forEach(snapshot -> storedByDate.put(snapshot.localDate(), snapshot));

        Set<LocalDate> missingDates = new TreeSet<>(requiredDates);
        missingDates.removeAll(storedByDate.keySet());

        Map<LocalDate, BigDecimal> fetchedTotals = new LinkedHashMap<>();
        Map<LocalDate, LinkedHashSet<String>> fetchedSources = new LinkedHashMap<>();
        LinkedHashSet<String> missingChains = new LinkedHashSet<>();
        if (!missingDates.isEmpty()) {
            PortfolioHistoryFetchRequest fetchRequest = new PortfolioHistoryFetchRequest(
                    scope.addresses(),
                    scope.resolvedChains(),
                    missingDates,
                    period);

            for (PortfolioHistoryProvider provider : historyProviders) {
                PortfolioHistoryFetchResult fetchResult = provider.fetchHistory(fetchRequest);
                missingChains.addAll(fetchResult.missingChains());
                fetchResult.totalsByDate().forEach((localDate, totalUsd) -> {
                    if (!missingDates.contains(localDate)) {
                        return;
                    }
                    fetchedTotals.merge(localDate, totalUsd, BigDecimal::add);
                    fetchedSources.computeIfAbsent(localDate, ignored -> new LinkedHashSet<>()).add(fetchResult.source());
                });
            }

            Instant now = Instant.now(clock);
            List<PortfolioHistorySnapshot> newSnapshots = fetchedTotals.entrySet().stream()
                    .map(entry -> new PortfolioHistorySnapshot(
                            scope.scopeHash(),
                            scope.addresses(),
                            scope.effectiveChains(),
                            entry.getKey(),
                            scaleUsd(entry.getValue()),
                            joinSources(fetchedSources.get(entry.getKey())),
                            !missingChains.isEmpty(),
                            List.copyOf(missingChains),
                            now))
                    .toList();
            if (!newSnapshots.isEmpty()) {
                historyRepository.upsertSnapshots(newSnapshots);
            }
        } else {
            storedSnapshots.stream()
                    .flatMap(snapshot -> snapshot.missingChains().stream())
                    .forEach(missingChains::add);
        }

        List<PortfolioHistoryResultPoint> points = new ArrayList<>();
        for (LocalDate date : requiredDates.stream().sorted().toList()) {
            PortfolioHistorySnapshot stored = storedByDate.get(date);
            if (stored != null) {
                points.add(new PortfolioHistoryResultPoint(
                        stored.localDate(),
                        stored.totalUsd(),
                        stored.source(),
                        true));
                missingChains.addAll(stored.missingChains());
                continue;
            }

            BigDecimal fetched = fetchedTotals.get(date);
            if (fetched == null) {
                continue;
            }
            points.add(new PortfolioHistoryResultPoint(
                    date,
                    scaleUsd(fetched),
                    joinSources(fetchedSources.get(date)),
                    false));
        }

        return new PortfolioHistoryResult(
                period,
                scope.scopeHash(),
                points.stream()
                        .sorted(Comparator.comparing(PortfolioHistoryResultPoint::localDate))
                        .toList(),
                !missingChains.isEmpty(),
                List.copyOf(missingChains),
                Instant.now(clock));
    }

    public void recordLiveSnapshot(List<String> addresses, List<String> chains, BigDecimal totalUsd) {
        Scope scope = normalizeScope(addresses, chains);
        Instant now = Instant.now(clock);
        PortfolioHistorySnapshot snapshot = new PortfolioHistorySnapshot(
                scope.scopeHash(),
                scope.addresses(),
                scope.effectiveChains(),
                LocalDate.now(clock.withZone(ZoneOffset.UTC)),
                scaleUsd(totalUsd),
                "live-overview",
                false,
                List.of(),
                now);
        historyRepository.upsertSnapshots(List.of(snapshot));
    }

    private Scope normalizeScope(List<String> addresses, List<String> chains) {
        List<String> normalizedAddresses = addresses.stream()
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(this::normalizeWalletKey)
                .distinct()
                .sorted()
                .toList();
        if (normalizedAddresses.isEmpty()) {
            throw new IllegalArgumentException("At least one address is required");
        }

        List<ChainDefinition> resolvedChains = chainCatalogService.resolve(chains);
        List<String> effectiveChains = new ArrayList<>(resolvedChains.stream().map(ChainDefinition::id).sorted().toList());
        effectiveChains.add(HYPERLIQUID_SCOPE);
        effectiveChains = effectiveChains.stream().distinct().sorted().toList();

        return new Scope(
                normalizedAddresses,
                resolvedChains,
                effectiveChains,
                scopeHash(normalizedAddresses, effectiveChains));
    }

    private String normalizeWalletKey(String address) {
        String trimmed = address == null ? "" : address.trim();
        return trimmed.matches("(?i)^0x[0-9a-f]{40}$") ? trimmed.toLowerCase(Locale.ROOT) : trimmed;
    }

    private String scopeHash(List<String> addresses, List<String> chains) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String payload = String.join(",", addresses) + "|" + String.join(",", chains);
            return HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 not available", exception);
        }
    }

    private Set<LocalDate> requiredDates(LocalDate fromDate, LocalDate toDate) {
        Set<LocalDate> dates = new LinkedHashSet<>();
        for (LocalDate current = fromDate; !current.isAfter(toDate); current = current.plusDays(1)) {
            dates.add(current);
        }
        return dates;
    }

    private BigDecimal scaleUsd(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private String joinSources(Set<String> sources) {
        if (sources == null || sources.isEmpty()) {
            return "unknown";
        }
        return String.join("+", sources);
    }

    private record Scope(
            List<String> addresses,
            List<ChainDefinition> resolvedChains,
            List<String> effectiveChains,
            String scopeHash) {
    }
}
