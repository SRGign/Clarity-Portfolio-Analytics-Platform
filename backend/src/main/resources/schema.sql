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

create table if not exists solana_defi_asset_registry (
    mint_address text primary key,
    symbol text not null,
    protocol_key text not null,
    protocol_name text not null,
    position_type text not null,
    decimals integer not null,
    source text not null,
    confidence text not null,
    enabled integer not null default 1,
    already_counted_in_spot_totals integer not null default 1,
    created_at text not null default current_timestamp,
    updated_at text not null default current_timestamp
);

insert or ignore into solana_defi_asset_registry (
    mint_address,
    symbol,
    protocol_key,
    protocol_name,
    position_type,
    decimals,
    source,
    confidence,
    enabled,
    already_counted_in_spot_totals
) values
    ('J1toso1uCk3RLmjorhTtrVwY9HJ7X8V9yYac6Y7kGCPn', 'JitoSOL', 'jito', 'Jito', 'LIQUID_STAKING', 9, 'official_jito_docs', 'VERIFIED', 1, 1),
    ('mSoLzYCxHdYgdzU16g5QSh3i5K3z3KZK7ytfqcJm7So', 'mSOL', 'marinade', 'Marinade', 'LIQUID_STAKING', 9, 'official_marinade_docs', 'VERIFIED', 1, 1),
    ('bSo13r4TkiE4KumL71LsHTPpL2euBYLFx6h9HP3piy1', 'bSOL', 'blazestake', 'BlazeStake', 'LIQUID_STAKING', 9, 'official_blazestake_docs', 'VERIFIED', 1, 1),
    ('5oVNBeEEQvYi1cX3ir8Dx5n1P7pdxydbGF2X4TxVusJm', 'INF', 'sanctum', 'Sanctum', 'LIQUID_STAKING', 9, 'official_sanctum_docs', 'VERIFIED', 1, 1),
    ('jupSoLaHXQiZZTSfEWMTRRgpnyFm8f6sZdosWBjx93v', 'JupSOL', 'jupiter', 'Jupiter', 'LIQUID_STAKING', 9, 'jupiter_verified_token_api', 'VERIFIED', 1, 1),
    ('BonK1YhkXEGLZzwtcvRTip3gAL9nCeQD7ppZBLXhtTs', 'bonkSOL', 'bonk', 'Bonk', 'LIQUID_STAKING', 9, 'official_sanctum_docs', 'VERIFIED', 1, 1),
    ('pWrSoLAhue6jUxUkbWgmEy5rD9VJzkFmvfTDV5KgNuu', 'pwrSOL', 'power', 'Power Staked SOL', 'LIQUID_STAKING', 9, 'official_sanctum_docs', 'VERIFIED', 1, 1),
    ('LSTxxxnJzKDFSLr4dUkPcmCf5VyryEqzPLz5j4bpxFp', 'LST', 'marginfi', 'MarginFi', 'LIQUID_STAKING', 9, 'jupiter_verified_token_api', 'VERIFIED', 1, 1),
    ('pSo1f9nQXWgXibFtKf7NWYxb5enAM4qfP6UJSiXRQfL', 'PSOL', 'phantom', 'Phantom SOL', 'LIQUID_STAKING', 9, 'jupiter_verified_token_api', 'VERIFIED', 1, 1),
    ('vSoLxydx6akxyMD9XEcPvGYNGq6Nn66oqVb3UkGkei7', 'vSOL', 'the-vault', 'The Vault', 'LIQUID_STAKING', 9, 'jupiter_verified_token_api', 'VERIFIED', 1, 1),
    ('7Q2afV64in6N6SeZsAAB81TJzwDoD6zpqmHkzi9Dcavn', 'JSOL', 'jpool', 'JPool', 'LIQUID_STAKING', 9, 'jupiter_verified_token_api', 'VERIFIED', 1, 1),
    ('Bybit2vBJGhPF52GBdNaQfUJ6ZpThSgHBobjWZpLPb4B', 'bbSOL', 'bybit', 'Bybit Staked SOL', 'LIQUID_STAKING', 9, 'jupiter_verified_token_api', 'VERIFIED', 1, 1),
    ('edge86g9cVz87xcpKpy3J77vbp4wYd9idEV562CCntt', 'edgeSOL', 'edgevana', 'Edgevana', 'LIQUID_STAKING', 9, 'jupiter_verified_token_api', 'VERIFIED', 1, 1),
    ('stke7uu3fXHsGqKVVjKnkmj65LRPVrqr4bLG2SJg7rh', 'STKESOL', 'sol-strategies', 'STKESOL by SOL Strategies', 'LIQUID_STAKING', 9, 'jupiter_verified_token_api', 'VERIFIED', 1, 1),
    ('jag58eRBC1c88LaAsRPspTMvoKJPbnzw9p9fREzHqyV', 'jagSOL', 'jagpool', 'JagPool Staked SOL', 'LIQUID_STAKING', 9, 'jupiter_verified_token_api', 'VERIFIED', 1, 1),
    ('sctmB7GPi5L2Q5G9tUSzXvhZ4YiDMEGcRov9KfArQpx', 'dfdvSOL', 'dfdv', 'DFDV Staked SOL', 'LIQUID_STAKING', 9, 'jupiter_verified_token_api', 'VERIFIED', 1, 1),
    ('he1iusmfkpAdwvxLNGV8Y1iSbj4rUy6yMhEA3fotn9A', 'hSOL', 'helius', 'Helius Staked SOL', 'LIQUID_STAKING', 9, 'jupiter_verified_token_api', 'VERIFIED', 1, 1),
    ('hy1oXYgrBW6PVcJ4s6s2FKavRdwgWTXdfE69AxT7kPT', 'hyloSOL', 'hylo', 'Hylo', 'LIQUID_STAKING', 9, 'jupiter_verified_token_api', 'VERIFIED', 1, 1),
    ('hy1opf2bqRDwAxoktyWAj6f3UpeHcLydzEdKjMYGs2u', 'hyloSOL+', 'hylo', 'Hylo SOL Plus', 'LIQUID_STAKING', 9, 'jupiter_verified_token_api', 'VERIFIED', 1, 1),
    ('roxDFxTFHufJBFy3PgzZcgz6kwkQNPZpi9RfpcAv4bu', 'RoXSOL', 'rockawayx', 'RockawayX', 'LIQUID_STAKING', 9, 'jupiter_verified_token_api', 'VERIFIED', 1, 1);

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
);

create index if not exists idx_solana_defi_position_snapshot_wallet_observed
    on solana_defi_position_snapshot(wallet_address, observed_at);

create index if not exists idx_solana_defi_position_snapshot_wallet_mint
    on solana_defi_position_snapshot(wallet_address, mint_address);

create index if not exists idx_solana_defi_position_snapshot_protocol
    on solana_defi_position_snapshot(protocol_key);
