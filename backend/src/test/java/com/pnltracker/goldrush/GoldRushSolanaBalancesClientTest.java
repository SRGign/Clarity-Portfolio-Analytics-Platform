package com.pnltracker.goldrush;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pnltracker.config.PortfolioProperties;
import com.pnltracker.goldrush.GoldRushSolanaBalancesClient.GoldRushSolanaBalanceItem;

class GoldRushSolanaBalancesClientTest {

    @Test
    void handlesMissingApiKeyGracefully() {
        PortfolioProperties properties = new PortfolioProperties();
        properties.getGoldrush().setApiKey("");
        GoldRushSolanaBalancesClient client = new GoldRushSolanaBalancesClient(properties, new ObjectMapper());

        List<GoldRushSolanaBalanceItem> balances = client.fetchBalances("SoLWallet111");

        assertThat(balances).isEmpty();
    }
}
