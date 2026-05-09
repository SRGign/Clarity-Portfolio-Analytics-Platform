package com.pnltracker.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pnltracker.domain.AssetBalance;
import com.pnltracker.domain.ChainAllocation;
import com.pnltracker.market.StableYieldMarket;
import com.pnltracker.market.StableYieldOpportunity;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

class AiAdvisorControllerTest {

    @Test
    void portfolioSummaryFallsBackWhenGeminiIsRateLimited() {
        PortfolioContextBuilder contextBuilder = mock(PortfolioContextBuilder.class);
        GeminiApiClient geminiApiClient = mock(GeminiApiClient.class);
        AiAdvisorController controller = new AiAdvisorController(
                contextBuilder,
                geminiApiClient,
                new PortfolioAdvisorFallback(),
                new ObjectMapper());

        when(contextBuilder.build(List.of("0xabc"), List.of("base"))).thenReturn(sampleContext());
        when(geminiApiClient.completeJson(anyString(), anyString()))
                .thenThrow(new AiAdvisorException("AI_RATE_LIMITED", "Gemini quota limit reached."));

        ResponseEntity<Map<String, Object>> response = controller.portfolioSummary(
                new AiAdvisorController.SummaryRequest(List.of("0xabc"), List.of("base")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).containsKeys("healthScore", "healthLabel", "risks", "opportunities", "actions");
        assertThat(response.getBody().get("caveat")).asString()
                .contains("Gemini quota was unavailable")
                .doesNotContain("on-chain data");
    }

    @Test
    void portfolioSummaryDoesNotFallbackWhenAiIsDisabled() {
        PortfolioContextBuilder contextBuilder = mock(PortfolioContextBuilder.class);
        GeminiApiClient geminiApiClient = mock(GeminiApiClient.class);
        AiAdvisorController controller = new AiAdvisorController(
                contextBuilder,
                geminiApiClient,
                new PortfolioAdvisorFallback(),
                new ObjectMapper());

        when(contextBuilder.build(List.of("0xabc"), List.of("base"))).thenReturn(sampleContext());
        when(geminiApiClient.completeJson(anyString(), anyString()))
                .thenThrow(new AiAdvisorException("AI_DISABLED", "Gemini API key is not configured"));

        ResponseEntity<Map<String, Object>> response = controller.portfolioSummary(
                new AiAdvisorController.SummaryRequest(List.of("0xabc"), List.of("base")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).containsEntry("error", "AI_DISABLED");
    }

    @Test
    void portfolioSummaryAcceptsJsonWrappedInMarkdownFence() {
        PortfolioContextBuilder contextBuilder = mock(PortfolioContextBuilder.class);
        GeminiApiClient geminiApiClient = mock(GeminiApiClient.class);
        AiAdvisorController controller = new AiAdvisorController(
                contextBuilder,
                geminiApiClient,
                new PortfolioAdvisorFallback(),
                new ObjectMapper());

        when(contextBuilder.build(List.of("0xabc"), List.of("base"))).thenReturn(sampleContext());
        when(geminiApiClient.completeJson(anyString(), anyString())).thenReturn("""
                ```json
                {
                  "healthScore": 72,
                  "healthLabel": "GOOD",
                  "oneLiner": "ETH concentration needs monitoring.",
                  "metrics": {
                    "concentrationRisk": "HIGH",
                    "liquidityScore": 80,
                    "yieldEfficiency": 90,
                    "diversificationScore": 65,
                    "idleStableUsd": 500
                  },
                  "risks": [],
                  "opportunities": [],
                  "actions": [],
                  "caveat": "model caveat should be replaced"
                }
                ```
                """);

        ResponseEntity<Map<String, Object>> response = controller.portfolioSummary(
                new AiAdvisorController.SummaryRequest(List.of("0xabc"), List.of("base")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("healthScore", 72);
        assertThat(response.getBody().get("caveat")).asString()
                .isEqualTo("Not financial advice. Market rates and portfolio values can change.")
                .doesNotContain("on-chain data")
                .doesNotContain("malformed JSON");
    }

    @Test
    void portfolioSummaryFallsBackWhenGeminiJsonCannotBeParsed() {
        PortfolioContextBuilder contextBuilder = mock(PortfolioContextBuilder.class);
        GeminiApiClient geminiApiClient = mock(GeminiApiClient.class);
        AiAdvisorController controller = new AiAdvisorController(
                contextBuilder,
                geminiApiClient,
                new PortfolioAdvisorFallback(),
                new ObjectMapper());

        when(contextBuilder.build(List.of("0xabc"), List.of("base"))).thenReturn(sampleContext());
        when(geminiApiClient.completeJson(anyString(), anyString()))
                .thenReturn("I cannot return JSON for this request.");

        ResponseEntity<Map<String, Object>> response = controller.portfolioSummary(
                new AiAdvisorController.SummaryRequest(List.of("0xabc"), List.of("base")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKeys("healthScore", "healthLabel", "risks", "opportunities", "actions");
        assertThat(response.getBody().get("caveat")).asString().contains("malformed JSON");
    }

    @Test
    void portfolioSummaryUsesRawProtocolWhenProtocolNameIsMissing() {
        PortfolioContextBuilder contextBuilder = mock(PortfolioContextBuilder.class);
        GeminiApiClient geminiApiClient = mock(GeminiApiClient.class);
        AiAdvisorController controller = new AiAdvisorController(
                contextBuilder,
                geminiApiClient,
                new PortfolioAdvisorFallback(),
                new ObjectMapper());

        when(contextBuilder.build(List.of("0xabc"), List.of("base"))).thenReturn(sampleContext(List.of(
                new AiDefiPosition(
                        "base",
                        "Aave",
                        "lending",
                        "deposit",
                        "USDC",
                        250.0d,
                        250.0d,
                        0.0d,
                        true))));
        when(geminiApiClient.completeJson(anyString(), anyString())).thenReturn("""
                {
                  "healthScore": 72,
                  "healthLabel": "GOOD",
                  "oneLiner": "Stable exposure needs deployment discipline.",
                  "metrics": {
                    "concentrationRisk": "MEDIUM",
                    "liquidityScore": 80,
                    "yieldEfficiency": 70,
                    "diversificationScore": 65,
                    "idleStableUsd": 500
                  },
                  "risks": [],
                  "opportunities": [],
                  "actions": [],
                  "caveat": "model caveat should be replaced"
                }
                """);

        controller.portfolioSummary(new AiAdvisorController.SummaryRequest(List.of("0xabc"), List.of("base")));

        ArgumentCaptor<String> contextCaptor = ArgumentCaptor.forClass(String.class);
        verify(geminiApiClient).completeJson(anyString(), contextCaptor.capture());
        assertThat(contextCaptor.getValue()).contains("Aave");
        assertThat(contextCaptor.getValue()).doesNotContain("Unknown protocol");
    }

    @Test
    void portfolioSummarySendsAddressRedactedContextToGemini() {
        PortfolioContextBuilder contextBuilder = mock(PortfolioContextBuilder.class);
        GeminiApiClient geminiApiClient = mock(GeminiApiClient.class);
        AiAdvisorController controller = new AiAdvisorController(
                contextBuilder,
                geminiApiClient,
                new PortfolioAdvisorFallback(),
                new ObjectMapper());

        when(contextBuilder.build(List.of("0xabc"), List.of("base"))).thenReturn(sampleContext(List.of(
                new AiDefiPosition(
                        "solana",
                        "Kamino Lend",
                        "lending",
                        "deposit",
                        "USDC",
                        1_000.0d,
                        1_000.0d,
                        0.0d,
                        false))));
        when(geminiApiClient.completeJson(anyString(), anyString())).thenReturn("""
                {
                  "healthScore": 72,
                  "healthLabel": "GOOD",
                  "oneLiner": "Stable exposure needs deployment discipline.",
                  "metrics": {
                    "concentrationRisk": "MEDIUM",
                    "liquidityScore": 80,
                    "yieldEfficiency": 70,
                    "diversificationScore": 65,
                    "idleStableUsd": 500
                  },
                  "risks": [],
                  "opportunities": [],
                  "actions": [],
                  "caveat": "model caveat should be replaced"
                }
                """);

        controller.portfolioSummary(new AiAdvisorController.SummaryRequest(List.of("0xabc"), List.of("base")));

        ArgumentCaptor<String> contextCaptor = ArgumentCaptor.forClass(String.class);
        verify(geminiApiClient).completeJson(anyString(), contextCaptor.capture());
        assertThat(contextCaptor.getValue())
                .contains("Kamino Lend")
                .doesNotContain("0xabc")
                .doesNotContain("walletAddress")
                .doesNotContain("wallet address");
    }

    @Test
    void portfolioSummaryIncludesConservativeStableYieldMarket() {
        PortfolioContextBuilder contextBuilder = mock(PortfolioContextBuilder.class);
        GeminiApiClient geminiApiClient = mock(GeminiApiClient.class);
        AiAdvisorController controller = new AiAdvisorController(
                contextBuilder,
                geminiApiClient,
                new PortfolioAdvisorFallback(),
                new ObjectMapper());

        when(contextBuilder.build(List.of("0xabc"), List.of("base"))).thenReturn(sampleContext(List.of(), new StableYieldMarket(
                List.of(new StableYieldOpportunity(
                        "pool-1",
                        "aave-v3",
                        "Aave V3",
                        "Ethereum",
                        "USDC",
                        4.2d,
                        4.2d,
                        0.0d,
                        250_000_000.0d,
                        1.75d,
                        "Conservative filter")),
                4.2d,
                java.time.Instant.parse("2026-05-08T00:00:00Z"),
                "DeFiLlama Yields",
                false)));
        when(geminiApiClient.completeJson(anyString(), anyString())).thenReturn("""
                {
                  "healthScore": 72,
                  "healthLabel": "GOOD",
                  "oneLiner": "Stable exposure has conservative yield options.",
                  "metrics": {
                    "concentrationRisk": "MEDIUM",
                    "liquidityScore": 80,
                    "yieldEfficiency": 70,
                    "diversificationScore": 65,
                    "idleStableUsd": 500
                  },
                  "risks": [],
                  "opportunities": [],
                  "actions": [],
                  "caveat": "model caveat should be replaced"
                }
                """);

        controller.portfolioSummary(new AiAdvisorController.SummaryRequest(List.of("0xabc"), List.of("base")));

        ArgumentCaptor<String> contextCaptor = ArgumentCaptor.forClass(String.class);
        verify(geminiApiClient).completeJson(anyString(), contextCaptor.capture());
        assertThat(contextCaptor.getValue()).contains("CONSERVATIVE STABLE YIELD MARKET");
        assertThat(contextCaptor.getValue()).contains("DeFiLlama Yields");
        assertThat(contextCaptor.getValue()).contains("Aave V3");
        assertThat(contextCaptor.getValue()).contains("protocol-specific TVL floors");
        assertThat(contextCaptor.getValue()).doesNotContain("TVL >= $50M");
        assertThat(contextCaptor.getValue()).contains("Market benchmark APY: 4.2%");
    }

    @Test
    void portfolioChatFormatsDenseRankedRepliesForDisplay() {
        PortfolioContextBuilder contextBuilder = mock(PortfolioContextBuilder.class);
        GeminiApiClient geminiApiClient = mock(GeminiApiClient.class);
        AiAdvisorController controller = new AiAdvisorController(
                contextBuilder,
                geminiApiClient,
                new PortfolioAdvisorFallback(),
                new ObjectMapper());

        when(contextBuilder.build(List.of("0xabc"), List.of("base"))).thenReturn(sampleContext());
        when(geminiApiClient.completeText(anyString(), any())).thenReturn(
                "You have idle stables. Options: 1. **SparkLend** on Ethereum - APY 4.0%. "
                        + "2. **Sky Lending** on Ethereum - APY 3.6%. "
                        + "Please remember this is not financial advice.");

        ResponseEntity<Map<String, Object>> response = controller.portfolioChat(
                new AiAdvisorController.ChatRequest(
                        List.of("0xabc"),
                        List.of("base"),
                        List.of(new AiAdvisorController.ChatMessage("user", "where can I deploy idle stables?"))));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("reply")).asString()
                .contains("Options:\n1. **SparkLend**")
                .contains("\n2. **Sky Lending**")
                .contains("\n\nPlease remember")
                .contains("**");
    }

    private static PortfolioContext sampleContext() {
        return sampleContext(List.of());
    }

    private static PortfolioContext sampleContext(List<AiDefiPosition> defiPositions) {
        return sampleContext(defiPositions, StableYieldMarket.empty("DeFiLlama Yields"));
    }

    private static PortfolioContext sampleContext(List<AiDefiPosition> defiPositions, StableYieldMarket stableYieldMarket) {
        return new PortfolioContext(
                10_000.0d,
                1,
                List.of(
                        new ChainAllocation("base", "Base", new BigDecimal("7000")),
                        new ChainAllocation("solana", "Solana", new BigDecimal("3000"))),
                List.of(
                        new TokenExposure("ETH", 5_000.0d, 5_000.0d, 0.0d, 50.0d),
                        new TokenExposure("USDC", 500.0d, 500.0d, 0.0d, 5.0d)),
                List.of(
                        new AssetBalance(
                                "base:eth",
                                "base",
                                null,
                                "ETH",
                                "Ethereum",
                                18,
                                new BigDecimal("2"),
                                new BigDecimal("2500"),
                                new BigDecimal("5000"),
                                true,
                                null),
                        new AssetBalance(
                                "base:usdc",
                                "base",
                                "0xusdc",
                                "USDC",
                                "USD Coin",
                                6,
                                new BigDecimal("500"),
                                BigDecimal.ONE,
                                new BigDecimal("500"),
                                false,
                                null)),
                defiPositions,
                defiPositions.stream()
                        .mapToDouble(AiDefiPosition::valueUsd)
                        .sum(),
                500.0d,
                0.0d,
                5.0d,
                2.5d,
                "ETH",
                50.0d,
                500.0d,
                null,
                stableYieldMarket);
    }
}
