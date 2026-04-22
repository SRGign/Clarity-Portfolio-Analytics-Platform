package com.pnltracker.zerion;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

@Service
public class ZerionDeFiService {

    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private record CachedEntry(List<ZerionPosition> positions, boolean stale, Instant expiresAt) {}

    private final ZerionApiClient client;
    // Maps address -> FutureTask<CachedEntry> so that concurrent requests for the same
    // address block on the in-flight fetch rather than each issuing a duplicate upstream call.
    private final ConcurrentHashMap<String, FutureTask<CachedEntry>> inFlight = new ConcurrentHashMap<>();
    // Separate map holds valid (non-expired) cached results for fast reads.
    private final ConcurrentHashMap<String, CachedEntry> cache = new ConcurrentHashMap<>();

    public ZerionDeFiService(ZerionApiClient client) {
        this.client = client;
    }

    public ZerionApiClient.ZerionFetchResult getPositions(String address) {
        String key = address.toLowerCase();

        // Fast path: valid cached entry exists.
        CachedEntry cached = cache.get(key);
        if (cached != null && Instant.now().isBefore(cached.expiresAt())) {
            return new ZerionApiClient.ZerionFetchResult(cached.positions(), cached.stale());
        }

        // Slow path: use computeIfAbsent so only one FutureTask is created per key.
        // All concurrent callers for the same key receive the same FutureTask and
        // block on future.get() — only one upstream fetch is made.
        FutureTask<CachedEntry> future = inFlight.computeIfAbsent(key, k -> {
            FutureTask<CachedEntry> task = new FutureTask<>(() -> fetchAndCache(k));
            task.run();
            return task;
        });

        try {
            CachedEntry entry = future.get();
            return new ZerionApiClient.ZerionFetchResult(entry.positions(), entry.stale());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ZerionApiClient.ZerionFetchResult(List.of(), true);
        } catch (ExecutionException e) {
            return new ZerionApiClient.ZerionFetchResult(List.of(), true);
        } finally {
            inFlight.remove(key, future);
        }
    }

    private CachedEntry fetchAndCache(String normalizedAddress) {
        ZerionApiClient.ZerionFetchResult result = client.getDeFiPositions(normalizedAddress);
        List<ZerionPosition> sorted = sortByGroupId(result.positions());
        Instant expiresAt = Instant.now().plus(CACHE_TTL);
        CachedEntry entry = new CachedEntry(sorted, result.stale(), expiresAt);
        cache.put(normalizedAddress, entry);
        return entry;
    }

    private List<ZerionPosition> sortByGroupId(List<ZerionPosition> positions) {
        if (positions == null || positions.isEmpty()) return List.of();

        List<ZerionPosition> mutable = new ArrayList<>(positions);
        mutable.sort(Comparator.comparing(
            ZerionPosition::groupId,
            Comparator.nullsLast(Comparator.naturalOrder())
        ));
        return List.copyOf(mutable);
    }
}
