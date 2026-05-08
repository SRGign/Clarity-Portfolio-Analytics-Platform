package com.pnltracker.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pnltracker.domain.AssetBalance;
import com.pnltracker.domain.ChainAllocation;
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
        assertThat(response.getBody().get("caveat")).asString().contains("Gemini quota was unavailable");
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

    private static PortfolioContext sampleContext() {
        return new PortfolioContext(
                10_000.0d,
                1,
                List.of(
                        new ChainAllocation("base", "Base", new BigDecimal("7000")),
                        new ChainAllocation("solana", "Solana", new BigDecimal("3000"))),
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
                List.of(),
                0.0d,
                5.0d,
                0.0d,
                "ETH",
                50.0d,
                500.0d);
    }
}
