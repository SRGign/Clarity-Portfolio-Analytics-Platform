package com.pnltracker.solana.defi.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Arrays;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pnltracker.config.PortfolioProperties;
import com.pnltracker.goldrush.GoldRushSolanaBalancesClient;
import com.pnltracker.goldrush.GoldRushSolanaBalancesClient.GoldRushSolanaBalanceItem;
import com.pnltracker.solana.defi.model.SolanaTokenizedDefiAssetDefinition;
import com.pnltracker.solana.defi.model.SolanaTokenizedDefiPosition;
import com.pnltracker.solana.defi.repository.SolanaDefiAssetRegistryRepository;
import com.pnltracker.solana.defi.repository.SolanaDefiPositionSnapshotRepository;

class SolanaTokenizedDefiDetectionServiceTest {

    private static final String JITOSOL_MINT = "J1toso1uCk3RLmjorhTtrVwY9HJ7X8V9yYac6Y7kGCPn";
    private static final String MSOL_MINT = "mSoLzYCxHdYgdzU16g5QSh3i5K3z3KZK7ytfqcJm7So";
    private static final String BSOL_MINT = "bSo13r4TkiE4KumL71LsHTPpL2euBYLFx6h9HP3piy1";
    private static final String JUPSOL_MINT = "jupSoLaHXQiZZTSfEWMTRRgpnyFm8f6sZdosWBjx93v";
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-04-24T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void detectsJitoSolByExactMint() {
        SolanaTokenizedDefiDetectionService service = serviceWithBalances(List.of(balance(JITOSOL_MINT, "FAKE_SYMBOL", "1000000000", "123.45", "123.45")));

        List<SolanaTokenizedDefiPosition> positions = service.detect("SoLWallet111");

        assertThat(positions).hasSize(1);
        assertThat(positions.get(0).mintAddress()).isEqualTo(JITOSOL_MINT);
        assertThat(positions.get(0).symbol()).isEqualTo("JitoSOL");
        assertThat(positions.get(0).protocolKey()).isEqualTo("jito");
        assertThat(positions.get(0).valueUsd()).isEqualByComparingTo("123.45");
    }

    @Test
    void detectsMSolByExactMint() {
        SolanaTokenizedDefiDetectionService service = serviceWithBalances(List.of(balance(MSOL_MINT, "anything", "2000000000", "110.00", "220.00")));

        List<SolanaTokenizedDefiPosition> positions = service.detect("SoLWallet111");

        assertThat(positions).hasSize(1);
        assertThat(positions.get(0).symbol()).isEqualTo("mSOL");
        assertThat(positions.get(0).protocolKey()).isEqualTo("marinade");
        assertThat(positions.get(0).quantity()).isEqualByComparingTo("2");
    }

    @Test
    void detectsBSolByExactMint() {
        SolanaTokenizedDefiDetectionService service = serviceWithBalances(List.of(balance(BSOL_MINT, "anything", "3000000000", "105.00", "315.00")));

        List<SolanaTokenizedDefiPosition> positions = service.detect("SoLWallet111");

        assertThat(positions).hasSize(1);
        assertThat(positions.get(0).symbol()).isEqualTo("bSOL");
        assertThat(positions.get(0).protocolKey()).isEqualTo("blazestake");
    }

    @Test
    void detectsJupSolByExactMint() {
        SolanaTokenizedDefiDetectionService service = serviceWithBalances(List.of(balance(JUPSOL_MINT, "not-trusted", "1000000000", "102.00", "102.00")));

        List<SolanaTokenizedDefiPosition> positions = service.detect("SoLWallet111");

        assertThat(positions).hasSize(1);
        assertThat(positions.get(0).symbol()).isEqualTo("JupSOL");
        assertThat(positions.get(0).protocolKey()).isEqualTo("jupiter");
        assertThat(positions.get(0).source()).isEqualTo("jupiter_verified_token_api");
    }

    @Test
    void doesNotDetectFakeTokenWithJitoSolSymbolAndDifferentMint() {
        SolanaTokenizedDefiDetectionService service = serviceWithBalances(List.of(balance("FakeMint111111111111111111111111111111111", "jitoSOL", "1000000000", "123.45", "123.45")));

        List<SolanaTokenizedDefiPosition> positions = service.detect("SoLWallet111");

        assertThat(positions).isEmpty();
    }

    @Test
    void ignoresZeroQuantityBalances() {
        SolanaTokenizedDefiDetectionService service = serviceWithBalances(List.of(balance(JITOSOL_MINT, "JitoSOL", "0", "123.45", "0")));

        List<SolanaTokenizedDefiPosition> positions = service.detect("SoLWallet111");

        assertThat(positions).isEmpty();
    }

    @Test
    void handlesMissingPriceAndValueWithoutThrowing() {
        SolanaTokenizedDefiDetectionService service = serviceWithBalances(List.of(new GoldRushSolanaBalanceItem(
                JITOSOL_MINT,
                "JitoSOL",
                "Jito Staked SOL",
                9,
                new BigDecimal("1000000000"),
                BigDecimal.ONE,
                null,
                null,
                null,
                "{}")));

        List<SolanaTokenizedDefiPosition> positions = service.detect("SoLWallet111");

        assertThat(positions).hasSize(1);
        assertThat(positions.get(0).priceUsd()).isNull();
        assertThat(positions.get(0).valueUsd()).isNull();
    }

    @Test
    void usesFallbackRegistryWhenDatabaseRegistryIsEmpty() {
        SolanaTokenizedDefiDetectionService service = serviceWithBalancesAndRegistry(
                List.of(balance(JITOSOL_MINT, "JitoSOL", "1000000000", "123.45", "123.45")),
                new FakeRegistryRepository(List.of(), false),
                new CapturingSnapshotRepository());

        List<SolanaTokenizedDefiPosition> positions = service.detect("SoLWallet111");

        assertThat(positions).hasSize(1);
        assertThat(positions.get(0).source()).isEqualTo("official_jito_docs");
    }

    @Test
    void usesFallbackRegistryWhenDatabaseRegistryFails() {
        SolanaTokenizedDefiDetectionService service = serviceWithBalancesAndRegistry(
                List.of(balance(MSOL_MINT, "mSOL", "1000000000", "110.00", "110.00")),
                new FakeRegistryRepository(List.of(), true),
                new CapturingSnapshotRepository());

        List<SolanaTokenizedDefiPosition> positions = service.detect("SoLWallet111");

        assertThat(positions).hasSize(1);
        assertThat(positions.get(0).protocolKey()).isEqualTo("marinade");
    }

    @Test
    void savesDetectedPositionSnapshots() {
        CapturingSnapshotRepository snapshots = new CapturingSnapshotRepository();
        SolanaTokenizedDefiDetectionService service = serviceWithBalancesAndRegistry(
                List.of(balance(BSOL_MINT, "bSOL", "1000000000", "105.00", "105.00")),
                new FakeRegistryRepository(List.of(), false),
                snapshots);

        service.detect("SoLWallet111");

        assertThat(snapshots.saved()).hasSize(1);
        assertThat(snapshots.saved().get(0).mintAddress()).isEqualTo(BSOL_MINT);
        assertThat(snapshots.saved().get(0).observedAt()).isEqualTo(Instant.parse("2026-04-24T12:00:00Z"));
    }

    @Test
    void detectionServiceDoesNotDependOnZerionFlow() {
        assertThat(Arrays.stream(SolanaTokenizedDefiDetectionService.class.getDeclaredFields())
                .map(field -> field.getType().getName()))
                .noneMatch(typeName -> typeName.contains(".zerion."));
    }

    @Test
    void fallbackRegistryContainsVerifiedAssets() {
        assertThat(new SolanaTokenizedDefiFallbackRegistry().enabledDefinitions())
                .extracting(SolanaTokenizedDefiAssetDefinition::mintAddress)
                .containsExactlyInAnyOrder(
                        JITOSOL_MINT,
                        MSOL_MINT,
                        BSOL_MINT,
                        "5oVNBeEEQvYi1cX3ir8Dx5n1P7pdxydbGF2X4TxVusJm",
                        JUPSOL_MINT,
                        "BonK1YhkXEGLZzwtcvRTip3gAL9nCeQD7ppZBLXhtTs",
                        "pWrSoLAhue6jUxUkbWgmEy5rD9VJzkFmvfTDV5KgNuu",
                        "LSTxxxnJzKDFSLr4dUkPcmCf5VyryEqzPLz5j4bpxFp",
                        "pSo1f9nQXWgXibFtKf7NWYxb5enAM4qfP6UJSiXRQfL",
                        "vSoLxydx6akxyMD9XEcPvGYNGq6Nn66oqVb3UkGkei7",
                        "7Q2afV64in6N6SeZsAAB81TJzwDoD6zpqmHkzi9Dcavn",
                        "Bybit2vBJGhPF52GBdNaQfUJ6ZpThSgHBobjWZpLPb4B",
                        "edge86g9cVz87xcpKpy3J77vbp4wYd9idEV562CCntt",
                        "stke7uu3fXHsGqKVVjKnkmj65LRPVrqr4bLG2SJg7rh",
                        "jag58eRBC1c88LaAsRPspTMvoKJPbnzw9p9fREzHqyV",
                        "sctmB7GPi5L2Q5G9tUSzXvhZ4YiDMEGcRov9KfArQpx",
                        "he1iusmfkpAdwvxLNGV8Y1iSbj4rUy6yMhEA3fotn9A",
                        "hy1oXYgrBW6PVcJ4s6s2FKavRdwgWTXdfE69AxT7kPT",
                        "hy1opf2bqRDwAxoktyWAj6f3UpeHcLydzEdKjMYGs2u",
                        "roxDFxTFHufJBFy3PgzZcgz6kwkQNPZpi9RfpcAv4bu");
    }


    private SolanaTokenizedDefiDetectionService serviceWithBalances(List<GoldRushSolanaBalanceItem> balances) {
        return serviceWithBalancesAndRegistry(
                balances,
                new FakeRegistryRepository(List.of(), false),
                new CapturingSnapshotRepository());
    }

    private SolanaTokenizedDefiDetectionService serviceWithBalancesAndRegistry(
            List<GoldRushSolanaBalanceItem> balances,
            SolanaDefiAssetRegistryRepository registryRepository,
            SolanaDefiPositionSnapshotRepository snapshotRepository) {
        return new SolanaTokenizedDefiDetectionService(
                new FakeGoldRushSolanaBalancesClient(balances),
                registryRepository,
                snapshotRepository,
                new SolanaTokenizedDefiFallbackRegistry(),
                FIXED_CLOCK);
    }

    private GoldRushSolanaBalanceItem balance(String mint, String symbol, String rawBalance, String priceUsd, String valueUsd) {
        BigDecimal raw = new BigDecimal(rawBalance);
        return new GoldRushSolanaBalanceItem(
                mint,
                symbol,
                symbol,
                9,
                raw,
                raw.movePointLeft(9).stripTrailingZeros(),
                priceUsd == null ? null : new BigDecimal(priceUsd),
                valueUsd == null ? null : new BigDecimal(valueUsd),
                null,
                "{\"contract_address\":\"" + mint + "\"}");
    }

    private static final class FakeGoldRushSolanaBalancesClient extends GoldRushSolanaBalancesClient {
        private final List<GoldRushSolanaBalanceItem> balances;

        private FakeGoldRushSolanaBalancesClient(List<GoldRushSolanaBalanceItem> balances) {
            super(new PortfolioProperties(), new ObjectMapper());
            this.balances = balances;
        }

        @Override
        public List<GoldRushSolanaBalanceItem> fetchBalances(String walletAddress) {
            return balances;
        }
    }

    private static final class FakeRegistryRepository implements SolanaDefiAssetRegistryRepository {
        private final List<SolanaTokenizedDefiAssetDefinition> definitions;
        private final boolean fail;

        private FakeRegistryRepository(List<SolanaTokenizedDefiAssetDefinition> definitions, boolean fail) {
            this.definitions = definitions;
            this.fail = fail;
        }

        @Override
        public List<SolanaTokenizedDefiAssetDefinition> findEnabledDefinitions() {
            if (fail) {
                throw new IllegalStateException("registry unavailable");
            }
            return definitions;
        }

        @Override
        public Optional<SolanaTokenizedDefiAssetDefinition> findEnabledByMintAddress(String mintAddress) {
            return definitions.stream()
                    .filter(definition -> definition.mintAddress().equals(mintAddress))
                    .findFirst();
        }
    }

    private static final class CapturingSnapshotRepository implements SolanaDefiPositionSnapshotRepository {
        private final List<SolanaTokenizedDefiPosition> saved = new ArrayList<>();

        @Override
        public void saveSnapshot(SolanaTokenizedDefiPosition position) {
            saved.add(position);
        }

        @Override
        public List<SolanaTokenizedDefiPosition> findLatestByWallet(String walletAddress) {
            return List.copyOf(saved);
        }

        private List<SolanaTokenizedDefiPosition> saved() {
            return List.copyOf(saved);
        }
    }
}
