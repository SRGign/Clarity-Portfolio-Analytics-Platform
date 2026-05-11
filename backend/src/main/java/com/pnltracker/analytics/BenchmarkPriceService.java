package com.pnltracker.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class BenchmarkPriceService {

    private static final String BITCOIN_ID = "coingecko:bitcoin";
    private static final String SOLANA_ID = "coingecko:solana";
    private static final Duration CACHE_TTL = Duration.ofHours(1);

    private final RestClient restClient;
    private final Map<Long, CachedBenchmark> cache = new ConcurrentHashMap<>();

    public BenchmarkPriceService() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(10));
        requestFactory.setReadTimeout(Duration.ofSeconds(20));
        this.restClient = RestClient.builder()
                .baseUrl("https://coins.llama.fi")
                .requestFactory(requestFactory)
                .build();
    }

    public BenchmarkData getBenchmarks(long startTimestamp) {
        CachedBenchmark cached = cache.get(startTimestamp);
        Instant now = Instant.now();
        if (cached != null && cached.expiresAt().isAfter(now)) {
            return cached.data();
        }

        BenchmarkData data = new BenchmarkData(
                fetchBenchmark(BITCOIN_ID, startTimestamp),
                fetchBenchmark(SOLANA_ID, startTimestamp));
        cache.put(startTimestamp, new CachedBenchmark(data, now.plus(CACHE_TTL)));
        return data;
    }

    private List<BenchmarkPoint> fetchBenchmark(String coinId, long startTimestamp) {
        JsonNode body = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/chart/{coinId}")
                        .queryParam("start", startTimestamp)
                        .queryParam("period", "1d")
                        .queryParam("span", 30)
                        .build(coinId))
                .retrieve()
                .body(JsonNode.class);

        JsonNode prices = body.path("coins").path(coinId).path("prices");
        if (!prices.isArray() || prices.isEmpty()) {
            return List.of();
        }

        double firstPrice = prices.get(0).path("price").asDouble(0.0d);
        if (firstPrice <= 0.0d) {
            return List.of();
        }

        List<BenchmarkPoint> points = new ArrayList<>();
        for (JsonNode pricePoint : prices) {
            long timestamp = pricePoint.path("timestamp").asLong();
            double price = pricePoint.path("price").asDouble(0.0d);
            if (price > 0.0d) {
                points.add(new BenchmarkPoint(timestamp, price / firstPrice * 100.0d));
            }
        }
        return List.copyOf(points);
    }

    private record CachedBenchmark(BenchmarkData data, Instant expiresAt) {
    }
}
