package com.pnltracker.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@Repository
public class JdbcPortfolioHistoryRepository implements PortfolioHistoryRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcPortfolioHistoryRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<PortfolioHistorySnapshot> findSnapshots(String scopeHash, LocalDate fromDate, LocalDate toDate) {
        return jdbcTemplate.query(
                """
                        select scope_hash, addresses_json, chains_json, local_date, total_usd, source, partial, missing_chains_json, created_at
                        from portfolio_history_snapshot
                        where scope_hash = ?
                          and local_date >= ?
                          and local_date <= ?
                        order by local_date asc
                        """,
                (resultSet, rowNum) -> mapSnapshot(resultSet),
                scopeHash,
                fromDate.toString(),
                toDate.toString());
    }

    @Override
    public void upsertSnapshots(List<PortfolioHistorySnapshot> snapshots) {
        for (PortfolioHistorySnapshot snapshot : snapshots) {
            jdbcTemplate.update(
                    """
                            insert into portfolio_history_snapshot (
                                scope_hash,
                                addresses_json,
                                chains_json,
                                local_date,
                                total_usd,
                                source,
                                partial,
                                missing_chains_json,
                                created_at
                            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                            on conflict(scope_hash, local_date) do update set
                                addresses_json = excluded.addresses_json,
                                chains_json = excluded.chains_json,
                                total_usd = excluded.total_usd,
                                source = excluded.source,
                                partial = excluded.partial,
                                missing_chains_json = excluded.missing_chains_json,
                                created_at = excluded.created_at
                            """,
                    snapshot.scopeHash(),
                    writeJson(snapshot.addresses()),
                    writeJson(snapshot.chains()),
                    snapshot.localDate().toString(),
                    snapshot.totalUsd(),
                    snapshot.source(),
                    snapshot.partial() ? 1 : 0,
                    writeJson(snapshot.missingChains()),
                    snapshot.createdAt().toString());
        }
    }

    private PortfolioHistorySnapshot mapSnapshot(ResultSet resultSet) throws SQLException {
        return new PortfolioHistorySnapshot(
                resultSet.getString("scope_hash"),
                readStringList(resultSet.getString("addresses_json")),
                readStringList(resultSet.getString("chains_json")),
                LocalDate.parse(resultSet.getString("local_date")),
                resultSet.getBigDecimal("total_usd"),
                resultSet.getString("source"),
                resultSet.getInt("partial") != 0,
                readStringList(resultSet.getString("missing_chains_json")),
                Instant.parse(resultSet.getString("created_at")));
    }

    private String writeJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to serialize history list", exception);
        }
    }

    private List<String> readStringList(String payload) {
        try {
            return objectMapper.readValue(payload, STRING_LIST);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to deserialize history list", exception);
        }
    }
}
