package com.pnltracker.solana.defi.repository;

import java.util.List;

import com.pnltracker.solana.defi.model.SolanaTokenizedDefiPosition;

public interface SolanaDefiPositionSnapshotRepository {

    void saveSnapshot(SolanaTokenizedDefiPosition position);

    List<SolanaTokenizedDefiPosition> findLatestByWallet(String walletAddress);
}
