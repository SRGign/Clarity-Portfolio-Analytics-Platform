package com.pnltracker.service;

import java.time.LocalDate;
import java.util.List;

public interface PortfolioHistoryRepository {

    List<PortfolioHistorySnapshot> findSnapshots(String scopeHash, LocalDate fromDate, LocalDate toDate);

    void upsertSnapshots(List<PortfolioHistorySnapshot> snapshots);
}
