package com.pnltracker.cli;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executor;

import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.pnltracker.config.AsyncConfig;
import com.pnltracker.config.PortfolioProperties;
import com.pnltracker.domain.AssetBalance;
import com.pnltracker.domain.PortfolioAnalysis;
import com.pnltracker.domain.ChainAllocation;
import com.pnltracker.hyperliquid.HyperliquidExchange;
import com.pnltracker.hyperliquid.HyperliquidInfoClient;
import com.pnltracker.hyperliquid.HyperliquidPerpPosition;
import com.pnltracker.hyperliquid.HyperliquidPortfolioWindow;
import com.pnltracker.hyperliquid.HyperliquidReport;
import com.pnltracker.hyperliquid.HyperliquidService;
import com.pnltracker.hyperliquid.HyperliquidSpotBalance;
import com.pnltracker.hyperliquid.HyperliquidSummary;
import com.pnltracker.hyperliquid.HyperliquidVaultEquity;
import com.pnltracker.provider.AlchemyPortfolioProvider;
import com.pnltracker.provider.AssetPortfolioProvider;
import com.pnltracker.provider.ProviderExchange;
import com.pnltracker.provider.ProviderFetchResult;
import com.pnltracker.service.ChainCatalogService;
import com.pnltracker.service.WrapperHeuristicLendingDetector;
import com.pnltracker.service.PortfolioService;
import com.pnltracker.service.SimpleTtlCache;

public final class PortfolioCliMain {

    private PortfolioCliMain() {
    }

    public static void main(String[] args) {
        CliArguments cliArguments = CliArguments.parse(args);
        if (cliArguments.addresses().isEmpty() && cliArguments.hyperliquidUser() == null) {
            printUsage();
            return;
        }

        PortfolioProperties properties = buildPortfolioProperties();
        ThreadPoolTaskExecutor alchemyExecutor = (ThreadPoolTaskExecutor) new AsyncConfig().alchemyExecutor(properties);
        try {
            ChainCatalogService chainCatalogService = new ChainCatalogService();
            AssetPortfolioProvider provider = new AlchemyPortfolioProvider(properties, alchemyExecutor);
            PortfolioService portfolioService = new PortfolioService(
                    provider,
                    chainCatalogService,
                    new SimpleTtlCache(properties),
                    new WrapperHeuristicLendingDetector(properties));
            HyperliquidService hyperliquidService = new HyperliquidService(new HyperliquidInfoClient());
            String effectiveHyperliquidUser = resolveHyperliquidUser(cliArguments);

            EvmResult evmResult = null;
            if (!cliArguments.addresses().isEmpty()) {
                List<String> networks = chainCatalogService.resolve(cliArguments.chains()).stream()
                        .map(chain -> chain.providerNetwork())
                        .toList();
                ProviderFetchResult fetchResult = provider.fetchAssetsWithDebug(cliArguments.addresses(), networks);
                PortfolioAnalysis analysis = portfolioService.analyzeProviderAssets(fetchResult.assets());
                evmResult = new EvmResult(networks, fetchResult, analysis);
            }

            HyperliquidReport hyperliquidReport = effectiveHyperliquidUser == null
                    ? null
                    : hyperliquidService.fetchReport(effectiveHyperliquidUser);

            if (evmResult != null) {
                printHeader(provider.providerName(), cliArguments.addresses(), evmResult.networks(), evmResult.fetchResult().skippedNetworks(), effectiveHyperliquidUser);
                printCombinedNetworkView(evmResult.analysis(), hyperliquidReport == null ? null : hyperliquidReport.summary());
                if (!cliArguments.summaryOnly()) {
                    printExchanges(evmResult.fetchResult().exchanges());
                }
                printSummary(evmResult.analysis());
            }

            if (hyperliquidReport != null) {
                if (!cliArguments.summaryOnly()) {
                    printHyperliquidExchanges(hyperliquidReport.exchanges());
                }
                printHyperliquidSummary(hyperliquidReport.summary());
            }
        } finally {
            alchemyExecutor.shutdown();
        }
    }

    private static String resolveHyperliquidUser(CliArguments cliArguments) {
        if (cliArguments.disableHyperliquid()) {
            return null;
        }
        if (cliArguments.hyperliquidUser() != null && !cliArguments.hyperliquidUser().isBlank()) {
            return cliArguments.hyperliquidUser();
        }
        return cliArguments.addresses().isEmpty() ? null : cliArguments.addresses().get(0);
    }

    private static PortfolioProperties buildPortfolioProperties() {
        PortfolioProperties properties = new PortfolioProperties();
        String apiKey = System.getenv("ALCHEMY_API_KEY");
        if (apiKey != null && !apiKey.isBlank()) {
            properties.getAlchemy().setApiKey(apiKey);
        }
        String goldRushApiKey = System.getenv("PORTFOLIO_GOLDRUSH_API_KEY");
        if ((goldRushApiKey == null || goldRushApiKey.isBlank())) {
            goldRushApiKey = System.getenv("GOLDRUSH_API_KEY");
        }
        if (goldRushApiKey != null && !goldRushApiKey.isBlank()) {
            properties.getGoldrush().setApiKey(goldRushApiKey);
        }
        return properties;
    }

    private static void printHeader(
            String providerName,
            List<String> addresses,
            List<String> networks,
            List<String> skippedNetworks,
            String hyperliquidUser) {
        System.out.println();
        System.out.println("=== Portfolio CLI ===");
        System.out.println("Provider: " + providerName);
        System.out.println("Addresses: " + String.join(", ", addresses));
        System.out.println("Networks selected: " + networks.size());
        System.out.println("Networks: " + String.join(", ", networks));
        if (hyperliquidUser != null) {
            System.out.println("Hyperliquid linked user: " + hyperliquidUser);
        }
        if (!skippedNetworks.isEmpty()) {
            System.out.println("Skipped unsupported by provider: " + String.join(", ", skippedNetworks));
        }
    }

    private static void printCombinedNetworkView(PortfolioAnalysis analysis, HyperliquidSummary hyperliquidSummary) {
        System.out.println();
        System.out.println("=== Combined Network View ===");
        List<ChainAllocation> allocations = new java.util.ArrayList<>(analysis.summary().allocations());
        if (hyperliquidSummary != null) {
            allocations.add(new ChainAllocation(
                    "hyperliquid",
                    "Hyperliquid",
                    hyperliquidSummary.latestPortfolioAccountValue()));
        }
        allocations = allocations.stream()
                .sorted(Comparator.comparing(ChainAllocation::valueUsd).reversed())
                .toList();

        java.math.BigDecimal combinedTotal = analysis.summary().totalUsd();
        if (hyperliquidSummary != null) {
            combinedTotal = combinedTotal.add(hyperliquidSummary.latestPortfolioAccountValue());
        }

        System.out.println("Combined total USD: " + combinedTotal);
        System.out.println("By network:");
        for (ChainAllocation allocation : allocations) {
            System.out.println(" - " + allocation.displayName() + ": $" + allocation.valueUsd());
        }
    }

    private static void printExchanges(List<ProviderExchange> exchanges) {
        System.out.println();
        System.out.println("=== Raw Provider Exchanges ===");
        int index = 1;
        for (ProviderExchange exchange : exchanges) {
            System.out.println("--- Exchange " + index + " ---");
            System.out.println("Addresses: " + String.join(", ", exchange.addresses()));
            System.out.println("Networks: " + String.join(", ", exchange.networks()));
            System.out.println("Page key: " + (exchange.pageKey() == null ? "<none>" : exchange.pageKey()));
            System.out.println("Request:");
            System.out.println(exchange.requestBody());
            System.out.println("Response:");
            System.out.println(exchange.responseBody());
            index++;
        }
    }

    private static void printSummary(PortfolioAnalysis analysis) {
        System.out.println();
        System.out.println("=== EVM Summary ===");
        System.out.println("Total USD: " + analysis.summary().totalUsd());
        System.out.println("Visible assets: " + analysis.summary().trackedAssets());
        System.out.println("Hidden assets: " + analysis.summary().hiddenAssets());

        System.out.println();
        System.out.println("By chain:");
        analysis.summary().allocations().forEach(allocation ->
                System.out.println(" - " + allocation.displayName() + ": $" + allocation.valueUsd()));

        System.out.println();
        System.out.println("Top assets:");
        analysis.assets().stream()
                .limit(15)
                .forEach(asset -> printAsset(asset));
    }

    private static void printAsset(AssetBalance asset) {
        System.out.println(
                " - " + asset.symbol()
                        + " | " + asset.network()
                        + " | qty=" + asset.quantity()
                        + " | price=" + asset.priceUsd()
                        + " | value=" + asset.valueUsd());
    }

    private static void printHyperliquidExchanges(List<HyperliquidExchange> exchanges) {
        System.out.println();
        System.out.println("=== Raw Hyperliquid Exchanges ===");
        int index = 1;
        for (HyperliquidExchange exchange : exchanges) {
            System.out.println("--- Hyperliquid Exchange " + index + " ---");
            System.out.println("Type: " + exchange.type());
            System.out.println("Request:");
            System.out.println(exchange.requestBody());
            System.out.println("Response:");
            System.out.println(exchange.responseBody());
            index++;
        }
    }

    private static void printHyperliquidSummary(HyperliquidSummary summary) {
        System.out.println();
        System.out.println("=== Hyperliquid Summary ===");
        System.out.println("User: " + summary.user());
        System.out.println("Role: " + summary.role());
        System.out.println("Latest portfolio account value: $" + summary.latestPortfolioAccountValue());
        System.out.println("Perp account value: $" + summary.perpAccountValue());
        System.out.println("Spot value: $" + summary.totalSpotValue());
        System.out.println("Vault equity: $" + summary.totalVaultEquity());
        System.out.println("Withdrawable: $" + summary.withdrawable());
        System.out.println("Perp notional: $" + summary.totalPerpNotional());
        System.out.println("Margin used: $" + summary.totalMarginUsed());

        System.out.println();
        System.out.println("Portfolio windows:");
        for (HyperliquidPortfolioWindow window : summary.portfolioWindows()) {
            System.out.println(" - " + window.period()
                    + " | accountValue=" + window.latestAccountValue()
                    + " | pnl=" + window.latestPnl()
                    + " | samples=" + window.accountValueSamples());
        }

        System.out.println();
        System.out.println("Spot balances:");
        if (summary.spotBalances().isEmpty()) {
            System.out.println(" - none");
        } else {
            for (HyperliquidSpotBalance balance : summary.spotBalances()) {
                System.out.println(" - " + balance.coin()
                        + " | total=" + balance.total()
                        + " | hold=" + balance.hold()
                        + " | price=" + display(balance.priceUsd())
                        + " | value=" + display(balance.valueUsd()));
            }
        }

        System.out.println();
        System.out.println("Perp positions:");
        if (summary.perpPositions().isEmpty()) {
            System.out.println(" - none");
        } else {
            for (HyperliquidPerpPosition position : summary.perpPositions()) {
                System.out.println(" - " + position.coin()
                        + " | size=" + position.size()
                        + " | positionValue=" + position.positionValue()
                        + " | upnl=" + position.unrealizedPnl()
                        + " | entry=" + position.entryPx()
                        + " | liq=" + display(position.liquidationPx()));
            }
        }

        System.out.println();
        System.out.println("Vault balances:");
        if (summary.vaultEquities().isEmpty()) {
            System.out.println(" - none");
        } else {
            for (HyperliquidVaultEquity vault : summary.vaultEquities()) {
                System.out.println(" - " + vault.vaultAddress() + " | equity=" + vault.equity());
            }
        }
    }

    private static String display(Object value) {
        return value == null ? "n/a" : value.toString();
    }

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("  .\\gradlew.bat portfolioCli --args=\"--addresses=0x...,0x... --chains=ethereum,base\"");
        System.out.println("  .\\gradlew.bat portfolioCli --args=\"--addresses=0x... --summary-only\"");
        System.out.println("  .\\gradlew.bat portfolioCli --args=\"--hyperliquid-user=0x... --summary-only\"");
        System.out.println("  .\\gradlew.bat portfolioCli --args=\"--addresses=0x... --hyperliquid-user=0x... --summary-only\"");
        System.out.println("  .\\gradlew.bat portfolioCli --args=\"--addresses=0x... --no-hyperliquid --summary-only\"");
        System.out.println();
        System.out.println("Notes:");
        System.out.println("  either --addresses or --hyperliquid-user is required");
        System.out.println("  --chains is optional; if omitted, all supported chains are queried");
        System.out.println("  if --addresses is provided, Hyperliquid is queried automatically for the first address");
        System.out.println("  use --no-hyperliquid to disable the automatic Hyperliquid lookup");
        System.out.println("  --summary-only hides raw provider exchanges and prints only the final summary");
    }

    private record CliArguments(
            List<String> addresses,
            List<String> chains,
            boolean summaryOnly,
            String hyperliquidUser,
            boolean disableHyperliquid) {
        private static CliArguments parse(String[] args) {
            List<String> addresses = List.of();
            List<String> chains = List.of();
            boolean summaryOnly = false;
            String hyperliquidUser = null;
            boolean disableHyperliquid = false;

            for (String arg : args) {
                if (arg.startsWith("--addresses=")) {
                    addresses = split(arg.substring("--addresses=".length()));
                }
                if (arg.startsWith("--chains=")) {
                    chains = split(arg.substring("--chains=".length()));
                }
                if ("--summary-only".equals(arg)) {
                    summaryOnly = true;
                }
                if (arg.startsWith("--hyperliquid-user=")) {
                    hyperliquidUser = arg.substring("--hyperliquid-user=".length()).trim();
                }
                if ("--no-hyperliquid".equals(arg)) {
                    disableHyperliquid = true;
                }
            }
            return new CliArguments(addresses, chains, summaryOnly, hyperliquidUser, disableHyperliquid);
        }

        private static List<String> split(String raw) {
            return Arrays.stream(raw.split(","))
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .toList();
        }
    }

    private record EvmResult(
            List<String> networks,
            ProviderFetchResult fetchResult,
            PortfolioAnalysis analysis) {
    }
}
