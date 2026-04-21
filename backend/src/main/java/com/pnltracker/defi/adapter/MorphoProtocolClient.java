package com.pnltracker.defi.adapter;

import java.math.BigDecimal;
import java.util.List;

public interface MorphoProtocolClient {

    List<MorphoMarketPosition> fetchPositions(List<String> addresses, List<String> networks);

    record MorphoMarketPosition(
            String walletAddress,
            String network,
            String marketId,
            String loanTokenAddress,
            String loanTokenSymbol,
            String loanTokenName,
            Integer loanTokenDecimals,
            String collateralTokenAddress,
            String collateralTokenSymbol,
            String collateralTokenName,
            Integer collateralTokenDecimals,
            BigDecimal supplyQuantity,
            BigDecimal supplyUsd,
            BigDecimal borrowQuantity,
            BigDecimal borrowUsd,
            BigDecimal collateralQuantity,
            BigDecimal collateralUsd) {
    }
}
