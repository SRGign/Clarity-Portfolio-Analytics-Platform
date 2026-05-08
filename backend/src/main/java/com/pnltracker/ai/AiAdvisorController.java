package com.pnltracker.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pnltracker.domain.AssetBalance;
import com.pnltracker.domain.ChainAllocation;
import com.pnltracker.market.StableYieldMarket;
import com.pnltracker.market.StableYieldOpportunity;
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
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/ai")
public class AiAdvisorController {

    private static final String SAFE_CAVEAT = "Not financial advice. Market rates and portfolio values can change.";
    private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {
    };
    private static final NumberFormat USD = NumberFormat.getCurrencyInstance(Locale.US);
    private static final DecimalFormat PCT = new DecimalFormat("0.0", DecimalFormatSymbols.getInstance(Locale.US));
    private static final DecimalFormat QTY = new DecimalFormat("0.########", DecimalFormatSymbols.getInstance(Locale.US));

    private final PortfolioContextBuilder contextBuilder;
    private final GeminiApiClient geminiApiClient;
    private final PortfolioAdvisorFallback portfolioAdvisorFallback;
    private final ObjectMapper objectMapper;

    public AiAdvisorController(
            PortfolioContextBuilder contextBuilder,
            GeminiApiClient geminiApiClient,
            PortfolioAdvisorFallback portfolioAdvisorFallback,
            ObjectMapper objectMapper) {
        this.contextBuilder = contextBuilder;
        this.geminiApiClient = geminiApiClient;
        this.portfolioAdvisorFallback = portfolioAdvisorFallback;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/portfolio-summary")
    public ResponseEntity<Map<String, Object>> portfolioSummary(@Valid @RequestBody SummaryRequest request) {
        try {
            PortfolioContext context = contextBuilder.build(request.addresses(), request.chains());
            String response;
            try {
                response = geminiApiClient.completeJson(
                        AiAdvisorSystemPrompt.ADVISOR,
                        formatContext(context));
            } catch (AiAdvisorException exception) {
                if (isFallbackEligible(exception)) {
                    return ResponseEntity.ok(portfolioAdvisorFallback.summarize(context));
                }
                throw exception;
            }
            return ResponseEntity.ok(parseSummaryResponse(response, context));
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

            String reply = normalizeChatReply(geminiApiClient.completeText(AiAdvisorSystemPrompt.CHAT, messages));
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
                .append(protocolLabel(position))
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

        appendStableYieldMarket(builder, context);
        return builder.toString();
    }

    private void appendStableYieldMarket(StringBuilder builder, PortfolioContext context) {
        StableYieldMarket market = context.stableYieldMarket();
        builder.append("\n=== CONSERVATIVE STABLE YIELD MARKET ===\n");
        builder.append("Source: DeFiLlama Yields\n");
        builder.append("Selection rules: stablecoin lending only, known protocols, single-asset exposure, protocol-specific TVL floors, APY <= 15%, no LP/farms/leverage.\n");
        builder.append("Idle stables available: ").append(money(context.idleStableUsd())).append("\n");

        if (market == null || !market.hasOpportunities()) {
            builder.append("No conservative stable lending venues are recommendable right now.\n");
            return;
        }

        if (market.benchmarkApy() != null) {
            builder.append("Market benchmark APY: ")
                    .append(PCT.format(market.benchmarkApy()))
                    .append("% (TVL-weighted across filtered pools)\n");
        }
        if (market.stale()) {
            builder.append("Market data status: using last cached yield market.\n");
        }
        builder.append("Top conservative options:\n");
        for (int index = 0; index < market.opportunities().size(); index++) {
            StableYieldOpportunity opportunity = market.opportunities().get(index);
            builder.append(index + 1)
                    .append(". ")
                    .append(opportunity.protocolName())
                    .append(" | ")
                    .append(opportunity.chain())
                    .append(" | ")
                    .append(opportunity.symbol())
                    .append(" | APY ")
                    .append(PCT.format(opportunity.apy()))
                    .append("%")
                    .append(" (base ")
                    .append(PCT.format(opportunity.apyBase()))
                    .append("%, rewards ")
                    .append(PCT.format(opportunity.apyReward()))
                    .append("%)")
                    .append(" | TVL ")
                    .append(money(opportunity.tvlUsd()))
                    .append(" | Estimated monthly yield ")
                    .append(money(opportunity.estimatedMonthlyYieldUsd()))
                    .append("\n");
        }
    }

    private ResponseEntity<Map<String, Object>> aiError(AiAdvisorException exception) {
        HttpStatus status = switch (exception.errorCode()) {
            case "AI_DISABLED" -> HttpStatus.SERVICE_UNAVAILABLE;
            case "AI_RATE_LIMITED" -> HttpStatus.TOO_MANY_REQUESTS;
            default -> HttpStatus.BAD_GATEWAY;
        };
        return ResponseEntity.status(status).body(error(exception.errorCode(), exception.getMessage()));
    }

    private boolean isFallbackEligible(AiAdvisorException exception) {
        return "AI_RATE_LIMITED".equals(exception.errorCode()) || "AI_UPSTREAM_ERROR".equals(exception.errorCode());
    }

    private String normalizeChatReply(String reply) {
        String text = reply == null ? "" : reply.trim();
        text = text.replaceAll("\\s+(?=\\d+\\.\\s)", "\n");
        text = text.replaceAll("\\s+(?=(Please remember|This is not financial advice|Not financial advice))", "\n\n");
        return text.replaceAll("\\n{3,}", "\n\n");
    }

    private Map<String, Object> parseSummaryResponse(String response, PortfolioContext context) {
        try {
            String json = extractJsonObject(response);
            JsonNode root = objectMapper.readTree(json);
            if (!root.isObject()) {
                throw new IllegalArgumentException("AI summary response must be a JSON object");
            }
            Map<String, Object> summary = objectMapper.convertValue(root, JSON_MAP);
            summary.put("caveat", SAFE_CAVEAT);
            return summary;
        } catch (Exception exception) {
            return portfolioAdvisorFallback.summarize(
                    context,
                    "Gemini returned malformed JSON, so this readout used deterministic portfolio rules.");
        }
    }

    private String extractJsonObject(String response) {
        String text = stripMarkdownFence(response == null ? "" : response.trim());
        int start = text.indexOf('{');
        if (start < 0) {
            return text;
        }

        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = start; index < text.length(); index++) {
            char current = text.charAt(index);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (current == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (current == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, index + 1);
                }
            }
        }
        return text;
    }

    private String stripMarkdownFence(String text) {
        if (!text.startsWith("```")) {
            return text;
        }
        int firstLineEnd = text.indexOf('\n');
        if (firstLineEnd < 0) {
            return text;
        }
        String withoutOpeningFence = text.substring(firstLineEnd + 1).trim();
        if (withoutOpeningFence.endsWith("```")) {
            return withoutOpeningFence.substring(0, withoutOpeningFence.length() - 3).trim();
        }
        return withoutOpeningFence;
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

    private String protocolLabel(ZerionPosition position) {
        return firstPresent(
                position.protocolName(),
                position.protocol(),
                position.dapp(),
                position.protocolModule(),
                "Unknown protocol");
    }

    private String firstPresent(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
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
