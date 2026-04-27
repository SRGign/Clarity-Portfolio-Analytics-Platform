package com.pnltracker.solana.defi.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import com.pnltracker.solana.defi.model.SolanaTokenizedDefiPosition;

class JdbcSolanaDefiPositionSnapshotRepositoryTest {

    private JdbcSolanaDefiPositionSnapshotRepository repository;

    @BeforeEach
    void setUp() {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        dataSource.setDriverClassName("org.sqlite.JDBC");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                create table if not exists solana_defi_position_snapshot (
                    id text primary key,
                    wallet_address text not null,
                    mint_address text not null,
                    symbol text not null,
                    protocol_key text not null,
                    protocol_name text not null,
                    position_type text not null,
                    quantity numeric not null,
                    price_usd numeric,
                    value_usd numeric,
                    source text not null,
                    confidence text not null,
                    observed_at text not null,
                    raw_payload_json text
                )
                """);
        repository = new JdbcSolanaDefiPositionSnapshotRepository(jdbcTemplate);
    }

    @Test
    void savesAndReadsLatestWalletSnapshots() {
        SolanaTokenizedDefiPosition older = position("SoLWallet111", "mint-a", "JitoSOL", "2026-04-24T10:00:00Z", "100.00");
        SolanaTokenizedDefiPosition latest = position("SoLWallet111", "mint-b", "mSOL", "2026-04-24T12:00:00Z", "120.00");
        repository.saveSnapshot(older);
        repository.saveSnapshot(latest);

        List<SolanaTokenizedDefiPosition> positions = repository.findLatestByWallet("SoLWallet111");

        assertThat(positions).hasSize(1);
        assertThat(positions.get(0).mintAddress()).isEqualTo("mint-b");
        assertThat(positions.get(0).valueUsd()).isEqualByComparingTo("120.00");
    }

    private SolanaTokenizedDefiPosition position(String wallet, String mint, String symbol, String observedAt, String valueUsd) {
        return new SolanaTokenizedDefiPosition(
                wallet,
                mint,
                symbol,
                "protocol",
                "Protocol",
                "LIQUID_STAKING",
                BigDecimal.ONE,
                new BigDecimal(valueUsd),
                new BigDecimal(valueUsd),
                "official_docs",
                "VERIFIED",
                true,
                Instant.parse(observedAt),
                "{}");
    }
}
