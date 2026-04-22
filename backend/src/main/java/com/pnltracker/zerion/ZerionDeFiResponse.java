package com.pnltracker.zerion;

import java.util.List;

public record ZerionDeFiResponse(
    List<ZerionPosition> positions,
    double totalUsd,
    boolean stale
) {}
