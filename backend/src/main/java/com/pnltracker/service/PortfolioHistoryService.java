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
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.pnltracker.domain.ChainDefinition;

@Service
public class PortfolioHistoryService {

    private static final String GOLDRUSH_SOURCE = "goldrush";
    private static final Pattern EVM_ADDRESS = Pattern.compile("^0x[0-9a-f]{40}$");

    private final ChainCatalogService chainCatalogService;
    private final PortfolioHistoryRepository historyRepository;
    private final PortfolioHistoryProvider historyProvider;
    private final Clock clock;

    @Autowired
    public PortfolioHistoryService(
            ChainCatalogService chainCatalogService,
            PortfolioHistoryRepository historyRepository,
            GoldRushPortfolioHistoryProvider goldRushPortfolioHistoryProvider) {
        this(chainCatalogService, historyRepository, goldRushPortfolioHistoryProvider, Clock.systemUTC());
    }

    PortfolioHistoryService(
            ChainCatalogService chainCatalogService,
            PortfolioHistoryRepository historyRepository,
            PortfolioHistoryProvider historyProvider,
            Clock clock) {
        this.chainCatalogService = chainCatalogService;
        this.historyRepository = historyRepository;
        this.historyProvider = historyProvider;
        this.clock = clock;
    }

    public PortfolioHistoryResult getHistory(List<String> addresses, List<String> chains, PortfolioHistoryPeriod period) {
        Scope scope = normalizeScope(addresses, chains);
        if (scope.addresses().isEmpty() || scope.resolvedChains().isEmpty()) {
            return new PortfolioHistoryResult(
                    period,
                    scope.scopeHash(),
                    List.of(),
                    false,
                    List.of(),
                    Instant.now(clock));
        }

        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        LocalDate fromDate = today.minusDays(period.lookbackDays());
        Set<LocalDate> requiredDates = requiredDates(fromDate, today);

        List<PortfolioHistorySnapshot> storedSnapshots = historyRepository.findSnapshots(scope.scopeHash(), fromDate, today).stream()
                .filter(snapshot -> GOLDRUSH_SOURCE.equals(snapshot.source()))
                .toList();
        Map<LocalDate, PortfolioHistorySnapshot> storedByDate = new LinkedHashMap<>();
        storedSnapshots.forEach(snapshot -> storedByDate.put(snapshot.localDate(), snapshot));

        Set<LocalDate> storedProviderDates = new TreeSet<>();
        storedSnapshots.stream()
                .map(PortfolioHistorySnapshot::localDate)
                .forEach(storedProviderDates::add);

        Set<LocalDate> missingDates = new TreeSet<>(requiredDates);
        missingDates.removeAll(storedProviderDates);

        Map<LocalDate, BigDecimal> fetchedTotals = new LinkedHashMap<>();
        Map<LocalDate, LinkedHashSet<String>> fetchedSources = new LinkedHashMap<>();
        LinkedHashSet<String> missingChains = new LinkedHashSet<>();
        if (!missingDates.isEmpty()) {
            PortfolioHistoryFetchRequest fetchRequest = new PortfolioHistoryFetchRequest(
                    scope.addresses(),
                    scope.resolvedChains(),
                    missingDates,
                    period);

            PortfolioHistoryFetchResult fetchResult = historyProvider.fetchHistory(fetchRequest);
            missingChains.addAll(fetchResult.missingChains());
            fetchResult.totalsByDate().forEach((localDate, totalUsd) -> {
                if (!missingDates.contains(localDate)) {
                    return;
                }
                fetchedTotals.put(localDate, totalUsd);
                fetchedSources.computeIfAbsent(localDate, ignored -> new LinkedHashSet<>()).add(fetchResult.source());
            });

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

        List<String> historyAddresses = normalizedAddresses.stream()
                .filter(this::isEvmAddress)
                .toList();

        List<ChainDefinition> resolvedChains = chainCatalogService.resolve(chains).stream()
                .filter(this::isGoldRushHistoryChain)
                .toList();
        List<String> effectiveChains = new ArrayList<>(resolvedChains.stream().map(ChainDefinition::id).sorted().toList());
        effectiveChains = effectiveChains.stream().distinct().sorted().toList();

        return new Scope(
                historyAddresses,
                resolvedChains,
                effectiveChains,
                scopeHash(historyAddresses, effectiveChains));
    }

    private String normalizeWalletKey(String address) {
        String trimmed = address == null ? "" : address.trim();
        return trimmed.matches("(?i)^0x[0-9a-f]{40}$") ? trimmed.toLowerCase(Locale.ROOT) : trimmed;
    }

    private boolean isEvmAddress(String address) {
        return EVM_ADDRESS.matcher(address).matches();
    }

    private boolean isGoldRushHistoryChain(ChainDefinition chain) {
        return "EVM".equalsIgnoreCase(chain.family());
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
