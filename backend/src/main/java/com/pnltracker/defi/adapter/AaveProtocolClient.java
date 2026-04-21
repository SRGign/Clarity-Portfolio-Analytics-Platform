package com.pnltracker.defi.adapter;

import java.math.BigDecimal;
import java.util.List;

public interface AaveProtocolClient {

    List<AaveUserReservePosition> fetchPositions(List<String> addresses, List<String> networks);

    record AaveUserReservePosition(
            String walletAddress,
            String network,
            String protocolKey,
            String protocolName,
            String underlyingTokenAddress,
            String underlyingSymbol,
            String underlyingName,
            BigDecimal supplyQuantity,
            BigDecimal priceUsd,
            BigDecimal supplyUsd,
            BigDecimal debtQuantity,
            BigDecimal debtUsd,
            boolean alreadyCountedInSpotTotals) {
    }
}
