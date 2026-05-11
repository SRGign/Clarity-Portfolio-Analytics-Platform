package com.pnltracker.market;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class StableYieldMarketServiceTest {

    @Test
    void parseMarketKeepsOnlyConservativeStableLendingPools() {
        DefiLlamaYieldProperties properties = new DefiLlamaYieldProperties();
        StableYieldMarketService service = new StableYieldMarketService(new ObjectMapper(), properties);

        StableYieldMarket market = service.parseMarket("""
                {
                  "data": [
                    {
                      "pool": "safe",
                      "project": "aave-v3",
                      "chain": "Ethereum",
                      "symbol": "USDC",
                      "tvlUsd": 250000000,
                      "apy": 4.2,
                      "apyBase": 4.2,
                      "apyReward": 0,
                      "stablecoin": true,
                      "exposure": "single",
                      "ilRisk": "no"
                    },
                    {
                      "pool": "small",
                      "project": "aave-v3",
                      "chain": "Ethereum",
                      "symbol": "USDC",
                      "tvlUsd": 1000000,
                      "apy": 6.0,
                      "stablecoin": true,
                      "exposure": "single",
                      "ilRisk": "no"
                    },
                    {
                      "pool": "farm",
                      "project": "curve-dex",
                      "chain": "Ethereum",
                      "symbol": "USDC-USDT",
                      "tvlUsd": 90000000,
                      "apy": 8.0,
                      "stablecoin": true,
                      "exposure": "multi",
                      "ilRisk": "yes"
                    },
                    {
                      "pool": "outlier",
                      "project": "compound-v3",
                      "chain": "Base",
                      "symbol": "USDC",
                      "tvlUsd": 120000000,
                      "apy": 28.0,
                      "stablecoin": true,
                      "exposure": "single",
                      "ilRisk": "no"
                    }
                  ]
                }
                """, 1_000.0d, false);

        assertThat(market.opportunities()).hasSize(1);
        StableYieldOpportunity opportunity = market.opportunities().get(0);
        assertThat(opportunity.protocolName()).isEqualTo("Aave V3");
        assertThat(opportunity.estimatedMonthlyYieldUsd()).isEqualTo(3.5d);
        assertThat(market.benchmarkApy()).isEqualTo(4.2d);
    }

    @Test
    void parseMarketAcceptsCurrentDefiLlamaYieldSlugs() {
        DefiLlamaYieldProperties properties = new DefiLlamaYieldProperties();
        StableYieldMarketService service = new StableYieldMarketService(new ObjectMapper(), properties);

        StableYieldMarket market = service.parseMarket("""
                {
                  "data": [
                    {
                      "pool": "sky",
                      "project": "sky-lending",
                      "chain": "Ethereum",
                      "symbol": "SUSDS",
                      "tvlUsd": 5013511235,
                      "apy": 3.65,
                      "apyBase": 3.65,
                      "stablecoin": true,
                      "exposure": "single",
                      "ilRisk": "no"
                    },
                    {
                      "pool": "spark",
                      "project": "sparklend",
                      "chain": "Ethereum",
                      "symbol": "USDS",
                      "tvlUsd": 1076761925,
                      "apy": 4.01,
                      "stablecoin": true,
                      "exposure": "single",
                      "ilRisk": "no"
                    },
                    {
                      "pool": "morpho",
                      "project": "morpho-blue",
                      "chain": "Base",
                      "symbol": "STEAKUSDC",
                      "tvlUsd": 468897131,
                      "apy": 4.04,
                      "stablecoin": true,
                      "exposure": "single",
                      "ilRisk": "no"
                    }
                  ]
                }
                """, 4_425.47d, false);

        assertThat(market.opportunities())
                .extracting(StableYieldOpportunity::protocolName)
                .containsExactly("Sky Lending", "SparkLend", "Morpho Blue");
        assertThat(market.benchmarkApy()).isNotNull();
    }

    @Test
    void parseMarketIncludesSolanaLendingRepresentatives() {
        DefiLlamaYieldProperties properties = new DefiLlamaYieldProperties();
        properties.setMaxOptions(8);
        StableYieldMarketService service = new StableYieldMarketService(new ObjectMapper(), properties);

        StableYieldMarket market = service.parseMarket("""
                {
                  "data": [
                    {
                      "pool": "sky",
                      "project": "sky-lending",
                      "chain": "Ethereum",
                      "symbol": "SUSDS",
                      "tvlUsd": 5013511235,
                      "apy": 3.65,
                      "stablecoin": true,
                      "exposure": "single",
                      "ilRisk": "no"
                    },
                    {
                      "pool": "spark",
                      "project": "sparklend",
                      "chain": "Ethereum",
                      "symbol": "USDS",
                      "tvlUsd": 1076761925,
                      "apy": 4.01,
                      "stablecoin": true,
                      "exposure": "single",
                      "ilRisk": "no"
                    },
                    {
                      "pool": "spark-usdc",
                      "project": "spark-savings",
                      "chain": "Ethereum",
                      "symbol": "USDC",
                      "tvlUsd": 920584731,
                      "apy": 3.65,
                      "stablecoin": true,
                      "exposure": "single",
                      "ilRisk": "no"
                    },
                    {
                      "pool": "morpho",
                      "project": "morpho-blue",
                      "chain": "Base",
                      "symbol": "STEAKUSDC",
                      "tvlUsd": 468897131,
                      "apy": 4.04,
                      "stablecoin": true,
                      "exposure": "single",
                      "ilRisk": "no"
                    },
                    {
                      "pool": "jupiter-usdc",
                      "project": "jupiter-lend",
                      "chain": "Solana",
                      "symbol": "USDC",
                      "tvlUsd": 429619283,
                      "apy": 4.42,
                      "apyBase": 3.32,
                      "apyReward": 1.10,
                      "stablecoin": true,
                      "exposure": "single",
                      "ilRisk": "no"
                    },
                    {
                      "pool": "jupiter-jupusd",
                      "project": "jupiter-lend",
                      "chain": "Solana",
                      "symbol": "JUPUSD",
                      "tvlUsd": 95337003,
                      "apy": 5.04,
                      "apyBase": 2.51,
                      "apyReward": 2.53,
                      "stablecoin": true,
                      "exposure": "single",
                      "ilRisk": "no"
                    },
                    {
                      "pool": "spark-usdt",
                      "project": "spark-savings",
                      "chain": "Ethereum",
                      "symbol": "USDT",
                      "tvlUsd": 396061402,
                      "apy": 2.50,
                      "stablecoin": true,
                      "exposure": "single",
                      "ilRisk": "no"
                    },
                    {
                      "pool": "kamino-pyusd",
                      "project": "kamino-lend",
                      "chain": "Solana",
                      "symbol": "PYUSD",
                      "tvlUsd": 11325935,
                      "apy": 2.64,
                      "stablecoin": true,
                      "exposure": "single",
                      "ilRisk": "no"
                    },
                    {
                      "pool": "kamino-usdc",
                      "project": "kamino-lend",
                      "chain": "Solana",
                      "symbol": "USDC",
                      "tvlUsd": 7945958,
                      "apy": 3.93,
                      "stablecoin": true,
                      "exposure": "single",
                      "ilRisk": "no"
                    }
                  ]
                }
                """, 4_425.66d, false);

        assertThat(market.opportunities())
                .extracting(StableYieldOpportunity::protocolName)
                .contains("Jupiter Lend", "Kamino Lend");
        assertThat(market.opportunities())
                .filteredOn(opportunity -> opportunity.protocolName().equals("Jupiter Lend"))
                .extracting(StableYieldOpportunity::symbol)
                .contains("USDC", "JUPUSD");
        assertThat(market.opportunities())
                .filteredOn(opportunity -> opportunity.protocolName().equals("Kamino Lend"))
                .extracting(StableYieldOpportunity::symbol)
                .contains("PYUSD", "USDC");
    }
}
