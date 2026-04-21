package com.pnltracker.service;

public interface PortfolioHistoryProvider {

    PortfolioHistoryFetchResult fetchHistory(PortfolioHistoryFetchRequest request);
}
