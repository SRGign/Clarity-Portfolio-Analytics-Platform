package com.pnltracker.api;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record PortfolioHistoryRequest(
        @NotEmpty List<@NotNull String> addresses,
        List<String> chains,
        @NotBlank @Pattern(regexp = "24h|7d|30d") String period) {
}
