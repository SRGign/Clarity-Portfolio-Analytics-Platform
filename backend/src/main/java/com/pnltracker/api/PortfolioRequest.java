package com.pnltracker.api;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public record PortfolioRequest(
        @NotEmpty List<@NotNull String> addresses,
        List<String> chains) {
}
