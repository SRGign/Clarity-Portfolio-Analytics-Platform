package com.pnltracker.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static java.util.Map.entry;

@Service
public class StableYieldMarketService {

    private static final String SOURCE = "DeFiLlama Yields";
    private static final Set<String> STABLE_SYMBOLS = Set.of(
            "USDC", "USDT", "DAI", "USDS", "PYUSD", "JUPUSD", "USDG", "FRAX", "LUSD", "EURC");
    private static final List<String> REPRESENTATIVE_PROJECTS = List.of("jupiter-lend", "kamino-lend");
    private static final Map<String, Double> MIN_TVL_USD_BY_PROJECT = Map.of(
            "kamino-lend", 5_000_000.0d);
    private static final Map<String, String> KNOWN_PROTOCOLS = Map.ofEntries(
            entry("aave-v3", "Aave V3"),
            entry("aave-v2", "Aave V2"),
            entry("compound-v3", "Compound V3"),
            entry("compound-v2", "Compound V2"),
            entry("morpho-blue", "Morpho Blue"),
            entry("morpho", "Morpho"),
            entry("sparklend", "SparkLend"),
            entry("spark-savings", "Spark Savings"),
            entry("spark", "Spark"),
            entry("sky-lending", "Sky Lending"),
            entry("sky", "Sky"),
            entry("jupiter-lend", "Jupiter Lend"),
            entry("kamino-lend", "Kamino Lend"));

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final DefiLlamaYieldProperties properties;
    private volatile CachedResponse cachedResponse;

    public StableYieldMarketService(ObjectMapper objectMapper, DefiLlamaYieldProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(8));
        requestFactory.setReadTimeout(Duration.ofSeconds(20));
        this.restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    public StableYieldMarket conservativeStableLending(double idleStableUsd) {
        CachedResponse response = freshCachedResponse();
        if (response == null) {
            response = fetchPools();
        }
        if (response == null) {
            return StableYieldMarket.empty(SOURCE);
        }
        return parseMarket(response.body(), Math.max(idleStableUsd, 0.0d), response.stale());
    }

    StableYieldMarket parseMarket(String responseBody, double idleStableUsd, boolean stale) {
        try {
            JsonNode data = objectMapper.readTree(responseBody).path("data");
            if (!data.isArray()) {
                return new StableYieldMarket(List.of(), null, Instant.now(), SOURCE, stale);
            }

            List<StableYieldOpportunity> filtered = java.util.stream.StreamSupport.stream(data.spliterator(), false)
                    .filter(this::isConservativeStableLendingPool)
                    .map(pool -> mapOpportunity(pool, idleStableUsd))
                    .sorted(Comparator
                            .comparingDouble(StableYieldOpportunity::tvlUsd)
                            .reversed()
                            .thenComparing(Comparator.comparingDouble(StableYieldOpportunity::apy).reversed()))
                    .toList();

            Double benchmarkApy = tvlWeightedApy(filtered);
            return new StableYieldMarket(
                    selectOptions(filtered),
                    benchmarkApy,
                    Instant.now(),
                    SOURCE,
                    stale);
        } catch (Exception exception) {
            return new StableYieldMarket(List.of(), null, Instant.now(), SOURCE, stale);
        }
    }

    private CachedResponse freshCachedResponse() {
        CachedResponse response = cachedResponse;
        if (response == null || Instant.now().isAfter(response.expiresAt())) {
            return null;
        }
        return response;
    }

    private CachedResponse fetchPools() {
        CachedResponse previous = cachedResponse;
        try {
            String body = restClient.get()
                    .uri("/pools")
                    .retrieve()
                    .body(String.class);
            CachedResponse next = new CachedResponse(
                    body == null ? "{\"data\":[]}" : body,
                    Instant.now().plusSeconds(Math.max(properties.getCacheTtlSeconds(), 60)),
                    false);
            cachedResponse = next;
            return next;
        } catch (Exception exception) {
            return previous == null ? null : new CachedResponse(previous.body(), previous.expiresAt(), true);
        }
    }

    private boolean isConservativeStableLendingPool(JsonNode pool) {
        String project = text(pool.path("project")).toLowerCase(Locale.ROOT);
        double tvlUsd = number(pool.path("tvlUsd"));
        double apy = number(pool.path("apy"));
        String symbol = text(pool.path("symbol")).toUpperCase(Locale.ROOT);
        String exposure = text(pool.path("exposure")).toLowerCase(Locale.ROOT);
        String ilRisk = text(pool.path("ilRisk")).toLowerCase(Locale.ROOT);

        return KNOWN_PROTOCOLS.containsKey(project)
                && pool.path("stablecoin").asBoolean(false)
                && referencesStableSymbol(symbol)
                && "single".equals(exposure)
                && !"yes".equals(ilRisk)
                && tvlUsd >= minTvlUsd(project)
                && apy > 0.0d
                && apy <= properties.getMaxApy();
    }

    private StableYieldOpportunity mapOpportunity(JsonNode pool, double idleStableUsd) {
        String project = text(pool.path("project")).toLowerCase(Locale.ROOT);
        double apy = number(pool.path("apy"));
        return new StableYieldOpportunity(
                text(pool.path("pool")),
                project,
                KNOWN_PROTOCOLS.getOrDefault(project, project),
                text(pool.path("chain")),
                text(pool.path("symbol")),
                round(apy),
                round(number(pool.path("apyBase"))),
                round(number(pool.path("apyReward"))),
                round(number(pool.path("tvlUsd"))),
                round(idleStableUsd * apy / 100.0d / 12.0d),
                "Conservative filter: stablecoin lending, known protocol, single-asset exposure, TVL above app floor.");
    }

    private List<StableYieldOpportunity> selectOptions(List<StableYieldOpportunity> sorted) {
        int maxOptions = Math.max(properties.getMaxOptions(), 1);
        List<StableYieldOpportunity> selected = new ArrayList<>(sorted.stream().limit(maxOptions).toList());

        for (String project : REPRESENTATIVE_PROJECTS) {
            representativeOptions(sorted, project)
                    .forEach(opportunity -> addRepresentativeOption(selected, opportunity, maxOptions));
        }

        selected.sort(Comparator
                .comparingDouble(StableYieldOpportunity::tvlUsd)
                .reversed()
                .thenComparing(Comparator.comparingDouble(StableYieldOpportunity::apy).reversed()));
        return selected;
    }

    private void addRepresentativeOption(
            List<StableYieldOpportunity> selected,
            StableYieldOpportunity representative,
            int maxOptions) {
        boolean alreadySelected = selected.stream()
                .anyMatch(opportunity -> opportunity.poolId().equals(representative.poolId()));
        if (alreadySelected) {
            return;
        }
        if (selected.size() < maxOptions) {
            selected.add(representative);
            return;
        }

        int replacementIndex = duplicateProjectReplacementIndex(selected);
        if (replacementIndex < 0) {
            replacementIndex = nonRepresentativeReplacementIndex(selected);
        }
        if (replacementIndex >= 0) {
            selected.set(replacementIndex, representative);
        }
    }

    private List<StableYieldOpportunity> representativeOptions(
            List<StableYieldOpportunity> sorted,
            String project) {
        List<StableYieldOpportunity> protocolOptions = sorted.stream()
                .filter(opportunity -> opportunity.protocol().equals(project))
                .toList();
        List<StableYieldOpportunity> representatives = new ArrayList<>();

        protocolOptions.stream()
                .max(Comparator.comparingDouble(StableYieldOpportunity::tvlUsd))
                .ifPresent(representatives::add);
        protocolOptions.stream()
                .max(Comparator.comparingDouble(StableYieldOpportunity::apy))
                .filter(opportunity -> representatives.stream()
                        .noneMatch(selected -> selected.poolId().equals(opportunity.poolId())))
                .ifPresent(representatives::add);

        return representatives;
    }

    private int duplicateProjectReplacementIndex(List<StableYieldOpportunity> selected) {
        for (int index = selected.size() - 1; index >= 0; index--) {
            StableYieldOpportunity candidate = selected.get(index);
            if (REPRESENTATIVE_PROJECTS.contains(candidate.protocol())) {
                continue;
            }
            long projectCount = selected.stream()
                    .filter(opportunity -> opportunity.protocol().equals(candidate.protocol()))
                    .count();
            if (projectCount > 1) {
                return index;
            }
        }
        return -1;
    }

    private int nonRepresentativeReplacementIndex(List<StableYieldOpportunity> selected) {
        for (int index = selected.size() - 1; index >= 0; index--) {
            if (!REPRESENTATIVE_PROJECTS.contains(selected.get(index).protocol())) {
                return index;
            }
        }
        return -1;
    }

    private double minTvlUsd(String project) {
        return MIN_TVL_USD_BY_PROJECT.getOrDefault(project, properties.getMinTvlUsd());
    }

    private boolean referencesStableSymbol(String symbol) {
        for (String stable : STABLE_SYMBOLS) {
            if (symbol.contains(stable)) {
                return true;
            }
        }
        return false;
    }

    private Double tvlWeightedApy(List<StableYieldOpportunity> opportunities) {
        double weighted = 0.0d;
        double tvl = 0.0d;
        for (StableYieldOpportunity opportunity : opportunities) {
            weighted += opportunity.apy() * opportunity.tvlUsd();
            tvl += opportunity.tvlUsd();
        }
        return tvl <= 0.0d ? null : round(weighted / tvl);
    }

    private String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        String value = node.asText("");
        return value == null ? "" : value.trim();
    }

    private double number(JsonNode node) {
        return node == null || !node.isNumber() ? 0.0d : node.doubleValue();
    }

    private double round(double value) {
        return Math.round(value * 100.0d) / 100.0d;
    }

    private record CachedResponse(String body, Instant expiresAt, boolean stale) {
    }
}
