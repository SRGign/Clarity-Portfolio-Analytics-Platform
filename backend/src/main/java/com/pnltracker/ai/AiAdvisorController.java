package com.pnltracker.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pnltracker.domain.AssetBalance;
import com.pnltracker.domain.ChainAllocation;
import com.pnltracker.zerion.ZerionPosition;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/ai")
public class AiAdvisorController {

    private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {
    };
    private static final NumberFormat USD = NumberFormat.getCurrencyInstance(Locale.US);
    private static final DecimalFormat PCT = new DecimalFormat("0.0");
    private static final DecimalFormat QTY = new DecimalFormat("0.########");

    private final PortfolioContextBuilder contextBuilder;
    private final GeminiApiClient geminiApiClient;
    private final ObjectMapper objectMapper;

    public AiAdvisorController(
            PortfolioContextBuilder contextBuilder,
            GeminiApiClient geminiApiClient,
            ObjectMapper objectMapper) {
        this.contextBuilder = contextBuilder;
        this.geminiApiClient = geminiApiClient;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/portfolio-summary")
    public ResponseEntity<Map<String, Object>> portfolioSummary(@Valid @RequestBody SummaryRequest request) {
        try {
            PortfolioContext context = contextBuilder.build(request.addresses(), request.chains());
            String response = geminiApiClient.completeJson(
                    AiAdvisorSystemPrompt.ADVISOR,
                    formatContext(context));
            try {
                return ResponseEntity.ok(objectMapper.readValue(response, JSON_MAP));
            } catch (Exception exception) {
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(error(
                        "AI_PARSE_ERROR",
                        "AI response could not be parsed as JSON"));
            }
        } catch (AiAdvisorException exception) {
            return aiError(exception);
        }
    }

    @PostMapping("/portfolio-chat")
    public ResponseEntity<Map<String, Object>> portfolioChat(@Valid @RequestBody ChatRequest request) {
        try {
            PortfolioContext context = contextBuilder.build(request.addresses(), request.chains());
            List<GeminiMessage> messages = new ArrayList<>();
            messages.add(new GeminiMessage(
                    "user",
                    "Here is my portfolio:\n" + formatContext(context) + "\nPlease confirm you have it."));
            messages.add(new GeminiMessage(
                    "model",
                    "Understood. I have your portfolio data loaded. I can see total value of "
                            + money(context.totalValueUsd()) + " across " + context.walletCount()
                            + " wallet(s). What would you like to know?"));
            request.messages().stream()
                    .map(message -> new GeminiMessage(message.role(), message.text()))
                    .forEach(messages::add);

            String reply = geminiApiClient.completeText(AiAdvisorSystemPrompt.CHAT, messages);
            return ResponseEntity.ok(Map.of("reply", reply));
        } catch (AiAdvisorException exception) {
            return aiError(exception);
        }
    }

    private String formatContext(PortfolioContext context) {
        StringBuilder builder = new StringBuilder();
        builder.append("=== PORTFOLIO SNAPSHOT ===\n");
        builder.append("Total Value: ").append(money(context.totalValueUsd())).append("\n");
        builder.append("Wallets tracked: ").append(context.walletCount()).append("\n\n");

        builder.append("=== CHAIN DISTRIBUTION ===\n");
        context.chainAllocations().stream()
                .sorted(Comparator.comparing(ChainAllocation::valueUsd).reversed())
                .forEach(allocation -> {
                    double value = amount(allocation.valueUsd());
                    builder.append(allocation.displayName())
                            .append(": ")
                            .append(PCT.format(pct(value, context.totalValueUsd())))
                            .append("% (")
                            .append(money(value))
                            .append(")\n");
                });

        builder.append("\n=== TOP HOLDINGS ===\n");
        context.topAssets().stream()
                .sorted(Comparator.comparing(AssetBalance::valueUsd).reversed())
                .limit(8)
                .forEach(asset -> {
                    double value = amount(asset.valueUsd());
                    builder.append(asset.symbol())
                            .append(": ")
                            .append(quantity(asset.quantity()))
                            .append(" = ")
                            .append(money(value))
                            .append(" (")
                            .append(PCT.format(pct(value, context.totalValueUsd())))
                            .append("% of portfolio)\n");
                });

        builder.append("\n=== ACTIVE DEFI POSITIONS ===\n");
        context.evmDefiPositions().forEach(position -> builder.append("[")
                .append(fallback(position.chain(), "unknown"))
                .append("] ")
                .append(fallback(position.protocolName(), "Unknown protocol"))
                .append(" | ")
                .append(fallback(position.positionType(), "position"))
                .append(" | ")
                .append(fallback(position.tokenSymbol(), "UNKNOWN"))
                .append(" | ")
                .append(money(position.value() == null ? 0.0d : position.value()))
                .append("\n"));
        builder.append("Total in DeFi: ")
                .append(money(context.totalDefiValueUsd()))
                .append(" (")
                .append(PCT.format(context.defiAsPctOfPortfolio()))
                .append("% of portfolio)\n");

        builder.append("\n=== KEY NUMBERS ===\n");
        builder.append("Stablecoin allocation: ")
                .append(PCT.format(context.stableAllocationPct()))
                .append("%\n");
        builder.append("Idle stables (earning 0%): ")
                .append(money(context.idleStableUsd()))
                .append("\n");
        builder.append("Largest single position: ")
                .append(context.largestPositionSymbol())
                .append(" at ")
                .append(PCT.format(context.largestPositionPct()))
                .append("%\n");
        return builder.toString();
    }

    private ResponseEntity<Map<String, Object>> aiError(AiAdvisorException exception) {
        HttpStatus status = "AI_DISABLED".equals(exception.errorCode())
                ? HttpStatus.SERVICE_UNAVAILABLE
                : HttpStatus.BAD_GATEWAY;
        return ResponseEntity.status(status).body(error(exception.errorCode(), exception.getMessage()));
    }

    private Map<String, Object> error(String code, String message) {
        return Map.of("error", code, "message", message);
    }

    private String money(double value) {
        return USD.format(value);
    }

    private String quantity(BigDecimal value) {
        return value == null ? "0" : QTY.format(value);
    }

    private double amount(BigDecimal value) {
        return value == null ? 0.0d : value.doubleValue();
    }

    private double pct(double value, double total) {
        return total <= 0.0d ? 0.0d : value / total * 100.0d;
    }

    private String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public record SummaryRequest(
            @NotEmpty List<String> addresses,
            List<String> chains) {
    }

    public record ChatRequest(
            @NotEmpty List<String> addresses,
            List<String> chains,
            @NotEmpty List<ChatMessage> messages) {
    }

    public record ChatMessage(String role, String text) {
    }
}
