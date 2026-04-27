package com.pnltracker.solana.defi.controller;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pnltracker.solana.defi.model.SolanaTokenizedDefiPosition;
import com.pnltracker.solana.defi.service.SolanaTokenizedDefiDetectionService;

@RestController
@RequestMapping("/api/v1/wallets")
public class SolanaTokenizedDefiController {

    private final SolanaTokenizedDefiDetectionService detectionService;

    public SolanaTokenizedDefiController(SolanaTokenizedDefiDetectionService detectionService) {
        this.detectionService = detectionService;
    }

    @GetMapping("/{address}/solana/tokenized-defi-positions")
    public SolanaTokenizedDefiPositionsResponse getTokenizedDefiPositions(@PathVariable String address) {
        List<SolanaTokenizedDefiPosition> positions = detectionService.detect(address);
        Instant observedAt = positions.stream()
                .map(SolanaTokenizedDefiPosition::observedAt)
                .filter(Objects::nonNull)
                .max(Instant::compareTo)
                .orElseGet(Instant::now);
        return new SolanaTokenizedDefiPositionsResponse(
                address,
                observedAt,
                positions.stream().map(this::toResponse).toList(),
                totalValueUsd(positions));
    }

    private SolanaTokenizedDefiPositionResponse toResponse(SolanaTokenizedDefiPosition position) {
        return new SolanaTokenizedDefiPositionResponse(
                position.mintAddress(),
                position.symbol(),
                position.protocolKey(),
                position.protocolName(),
                position.positionType(),
                position.quantity(),
                position.priceUsd(),
                position.valueUsd(),
                position.source(),
                position.confidence(),
                position.alreadyCountedInSpotTotals());
    }

    private BigDecimal totalValueUsd(List<SolanaTokenizedDefiPosition> positions) {
        return positions.stream()
                .map(SolanaTokenizedDefiPosition::valueUsd)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    public record SolanaTokenizedDefiPositionsResponse(
            String walletAddress,
            Instant observedAt,
            List<SolanaTokenizedDefiPositionResponse> positions,
            BigDecimal totalValueUsd) {
    }

    public record SolanaTokenizedDefiPositionResponse(
            String mintAddress,
            String symbol,
            String protocolKey,
            String protocolName,
            String positionType,
            BigDecimal quantity,
            BigDecimal priceUsd,
            BigDecimal valueUsd,
            String source,
            String confidence,
            boolean alreadyCountedInSpotTotals) {
    }
}
