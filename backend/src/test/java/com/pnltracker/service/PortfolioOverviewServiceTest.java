package com.pnltracker.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.pnltracker.config.PortfolioProperties;
import com.pnltracker.domain.PortfolioAnalysis;
import com.pnltracker.domain.PortfolioSummary;
import com.pnltracker.hyperliquid.HyperliquidPortfolioService;

class PortfolioOverviewServiceTest {

    @Test
    void preservesSolanaAddressCaseForOverviewFetches() {
        PortfolioService portfolioService = mock(PortfolioService.class);
        HyperliquidPortfolioService hyperliquidPortfolioService = mock(HyperliquidPortfolioService.class);
        PortfolioOverviewService overviewService = new PortfolioOverviewService(
                portfolioService,
                hyperliquidPortfolioService,
                new SimpleTtlCache(new PortfolioProperties()));

        String solanaAddress = "38ueECEazDdLDovnKZLpaKhRodCQpZoJUHLo85bXoYgM";
        when(portfolioService.getAnalysis(anyList(), anyList())).thenReturn(emptyAnalysis());
        when(hyperliquidPortfolioService.getAnalysis(anyList())).thenReturn(emptyAnalysis());

        overviewService.getOverview(List.of(" " + solanaAddress + " "), List.of("solana"));

        ArgumentCaptor<List<String>> portfolioAddresses = ArgumentCaptor.forClass(List.class);
        verify(portfolioService).getAnalysis(portfolioAddresses.capture(), anyList());
        assertThat(portfolioAddresses.getValue()).containsExactly(solanaAddress);

        ArgumentCaptor<List<String>> hyperliquidAddresses = ArgumentCaptor.forClass(List.class);
        verify(hyperliquidPortfolioService).getAnalysis(hyperliquidAddresses.capture());
        assertThat(hyperliquidAddresses.getValue()).isEmpty();
    }

    private static PortfolioAnalysis emptyAnalysis() {
        return new PortfolioAnalysis(
                List.of(),
                new PortfolioSummary(BigDecimal.ZERO.setScale(2), 0, 0, List.of(), List.of()),
                List.of(),
                com.pnltracker.domain.LendingPositionSummary.empty());
    }
}
