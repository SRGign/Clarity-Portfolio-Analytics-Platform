package com.pnltracker.solana.defi.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.pnltracker.solana.defi.model.SolanaTokenizedDefiPosition;

@Repository
public class JdbcSolanaDefiPositionSnapshotRepository implements SolanaDefiPositionSnapshotRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcSolanaDefiPositionSnapshotRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void saveSnapshot(SolanaTokenizedDefiPosition position) {
        jdbcTemplate.update(
                """
                        insert into solana_defi_position_snapshot (
                            id,
                            wallet_address,
                            mint_address,
                            symbol,
                            protocol_key,
                            protocol_name,
                            position_type,
                            quantity,
                            price_usd,
                            value_usd,
                            source,
                            confidence,
                            observed_at,
                            raw_payload_json
                        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                UUID.randomUUID().toString(),
                position.walletAddress(),
                position.mintAddress(),
                position.symbol(),
                position.protocolKey(),
                position.protocolName(),
                position.positionType(),
                position.quantity(),
                position.priceUsd(),
                position.valueUsd(),
                position.source(),
                position.confidence(),
                position.observedAt().toString(),
                position.rawPayloadJson());
    }

    @Override
    public List<SolanaTokenizedDefiPosition> findLatestByWallet(String walletAddress) {
        if (walletAddress == null || walletAddress.isBlank()) {
            return List.of();
        }
        return jdbcTemplate.query(
                """
                        select wallet_address, mint_address, symbol, protocol_key, protocol_name, position_type,
                               quantity, price_usd, value_usd, source, confidence, observed_at, raw_payload_json
                        from solana_defi_position_snapshot
                        where wallet_address = ?
                          and observed_at = (
                              select max(observed_at)
                              from solana_defi_position_snapshot
                              where wallet_address = ?
                          )
                        order by value_usd desc, symbol asc
                        """,
                (resultSet, rowNum) -> mapPosition(resultSet),
                walletAddress.trim(),
                walletAddress.trim());
    }

    private SolanaTokenizedDefiPosition mapPosition(ResultSet resultSet) throws SQLException {
        return new SolanaTokenizedDefiPosition(
                resultSet.getString("wallet_address"),
                resultSet.getString("mint_address"),
                resultSet.getString("symbol"),
                resultSet.getString("protocol_key"),
                resultSet.getString("protocol_name"),
                resultSet.getString("position_type"),
                resultSet.getBigDecimal("quantity"),
                resultSet.getBigDecimal("price_usd"),
                resultSet.getBigDecimal("value_usd"),
                resultSet.getString("source"),
                resultSet.getString("confidence"),
                true,
                Instant.parse(resultSet.getString("observed_at")),
                resultSet.getString("raw_payload_json"));
    }
}
