create table if not exists portfolio_history_snapshot (
    scope_hash text not null,
    addresses_json text not null,
    chains_json text not null,
    local_date text not null,
    total_usd numeric not null,
    source text not null,
    partial integer not null,
    missing_chains_json text not null,
    created_at text not null,
    primary key (scope_hash, local_date)
);
