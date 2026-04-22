package com.pnltracker.zerion;

public record ZerionPosition(
    String positionType,
    Double value,
    Double price,
    Double quantity,
    Integer decimals,
    Double absoluteChange1d,
    Double percentChange1d,
    String protocol,
    String protocolModule,
    String poolAddress,
    String groupId,
    String protocolName,
    String protocolUrl,
    String tokenName,
    String tokenSymbol,
    String tokenIconUrl,
    String chainId,
    String tokenAddress,
    String updatedAt,
    String chain,
    String dapp
) {}
