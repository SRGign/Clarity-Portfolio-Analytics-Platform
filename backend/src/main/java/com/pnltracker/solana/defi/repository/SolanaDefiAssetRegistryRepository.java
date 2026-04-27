package com.pnltracker.solana.defi.repository;

import java.util.List;
import java.util.Optional;

import com.pnltracker.solana.defi.model.SolanaTokenizedDefiAssetDefinition;

public interface SolanaDefiAssetRegistryRepository {

    List<SolanaTokenizedDefiAssetDefinition> findEnabledDefinitions();

    Optional<SolanaTokenizedDefiAssetDefinition> findEnabledByMintAddress(String mintAddress);
}
