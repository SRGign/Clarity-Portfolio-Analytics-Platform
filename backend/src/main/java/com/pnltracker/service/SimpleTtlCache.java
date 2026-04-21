package com.pnltracker.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.pnltracker.config.PortfolioProperties;

@Component
public class SimpleTtlCache {

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final Clock clock;
    private final long ttlSeconds;

    @Autowired
    public SimpleTtlCache(PortfolioProperties properties) {
        this(properties, Clock.systemUTC());
    }

    SimpleTtlCache(PortfolioProperties properties, Clock clock) {
        this.ttlSeconds = properties.getCacheTtlSeconds();
        this.clock = clock;
    }

    @SuppressWarnings("unchecked")
    public <T> T getOrCompute(String key, Supplier<T> supplier) {
        Instant now = Instant.now(clock);
        CacheEntry current = cache.get(key);
        if (current != null && current.expiresAt().isAfter(now)) {
            return (T) current.value();
        }
        T value = supplier.get();
        cache.put(key, new CacheEntry(value, now.plusSeconds(ttlSeconds)));
        return value;
    }

    private record CacheEntry(Object value, Instant expiresAt) {
    }
}
