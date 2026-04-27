package com.pnltracker.solana.defi.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.pnltracker.solana.defi.model.SolanaTokenizedDefiAssetDefinition;

@Repository
public class JdbcSolanaDefiAssetRegistryRepository implements SolanaDefiAssetRegistryRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcSolanaDefiAssetRegistryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<SolanaTokenizedDefiAssetDefinition> findEnabledDefinitions() {
        return jdbcTemplate.query(
                """
                        select mint_address, symbol, protocol_key, protocol_name, position_type, decimals,
                               source, confidence, enabled, already_counted_in_spot_totals
                        from solana_defi_asset_registry
                        where enabled = 1
                        order by protocol_key asc, symbol asc
                        """,
                (resultSet, rowNum) -> mapDefinition(resultSet));
    }

    @Override
    public Optional<SolanaTokenizedDefiAssetDefinition> findEnabledByMintAddress(String mintAddress) {
        if (mintAddress == null || mintAddress.isBlank()) {
            return Optional.empty();
        }
        List<SolanaTokenizedDefiAssetDefinition> matches = jdbcTemplate.query(
                """
                        select mint_address, symbol, protocol_key, protocol_name, position_type, decimals,
                               source, confidence, enabled, already_counted_in_spot_totals
                        from solana_defi_asset_registry
                        where enabled = 1
                          and mint_address = ?
                        """,
                (resultSet, rowNum) -> mapDefinition(resultSet),
                mintAddress.trim());
        return matches.stream().findFirst();
    }

    private SolanaTokenizedDefiAssetDefinition mapDefinition(ResultSet resultSet) throws SQLException {
        return new SolanaTokenizedDefiAssetDefinition(
                resultSet.getString("mint_address"),
                resultSet.getString("symbol"),
                resultSet.getString("protocol_key"),
                resultSet.getString("protocol_name"),
                resultSet.getString("position_type"),
                resultSet.getInt("decimals"),
                resultSet.getString("source"),
                resultSet.getString("confidence"),
                resultSet.getInt("enabled") != 0,
                resultSet.getInt("already_counted_in_spot_totals") != 0);
    }
}
