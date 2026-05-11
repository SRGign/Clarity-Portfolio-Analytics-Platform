package com.pnltracker.ai;

import com.pnltracker.domain.ChainAllocation;
import com.pnltracker.market.StableYieldMarket;
import org.springframework.stereotype.Component;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class PortfolioAdvisorFallback {

    private static final String SAFE_CAVEAT = "Not financial advice. Market rates and portfolio values can change.";
    private static final NumberFormat USD = NumberFormat.getCurrencyInstance(Locale.US);
    private static final Set<String> CORE_MAJOR_SYMBOLS = Set.of(
            "BTC",
            "WBTC",
            "CBBTC",
            "TBTC",
            "RENBTC",
            "XBT",
            "ETH",
            "WETH",
            "WETHE",
            "STETH",
            "WSTETH",
            "RETH",
            "CBETH",
            "FRXETH",
            "SFRXETH",
            "METH",
            "WEETH",
            "EZETH",
            "OSETH",
            "SWETH");

    public Map<String, Object> summarize(PortfolioContext context) {
        return summarize(context, "");
    }

    public Map<String, Object> summarize(PortfolioContext context, String fallbackReason) {
        double total = Math.max(context.totalValueUsd(), 0.0d);
        double largestPct = clamp(context.largestPositionPct(), 0.0d, 100.0d);
        double stablePct = clamp(context.stableAllocationPct(), 0.0d, 100.0d);
        double defiPct = clamp(context.defiAsPctOfPortfolio(), 0.0d, 100.0d);
        double largestChainPct = largestChainPct(context);
        double idleStableUsd = Math.max(context.idleStableUsd(), 0.0d);
        double benchmarkApy = yieldBenchmarkApy(context.stableYieldMarket());
        boolean coreMajorAsset = isCoreMajorAsset(context.largestPositionSymbol());

        int concentrationPenalty = concentrationPenalty(largestPct, coreMajorAsset);
        int chainPenalty = largestChainPct >= 75.0d ? 18 : largestChainPct >= 60.0d ? 12 : 0;
        int stablePenalty = stablePct < 10.0d ? 12 : 0;
        int defiPenalty = defiPct > 55.0d ? 16 : defiPct > 40.0d ? 10 : 0;
        int idlePenalty = idleStableUsd > Math.max(total * 0.1d, 1_000.0d) ? 8 : 0;

        int healthScore = clampToInt(88 - concentrationPenalty - chainPenalty - stablePenalty - defiPenalty - idlePenalty, 0, 100);
        String concentrationRisk = concentrationRisk(largestPct, largestChainPct, coreMajorAsset);

        return Map.of(
                "healthScore", healthScore,
                "healthLabel", healthLabel(healthScore),
                "oneLiner", oneLiner(concentrationRisk, context.largestPositionSymbol(), largestPct, largestChainPct, coreMajorAsset),
                "metrics", Map.of(
                        "concentrationRisk", concentrationRisk,
                        "liquidityScore", liquidityScore(stablePct, defiPct),
                        "yieldEfficiency", yieldEfficiency(total, idleStableUsd),
                        "diversificationScore", diversificationScore(largestPct, largestChainPct, coreMajorAsset),
                        "idleStableUsd", roundMoney(idleStableUsd)),
                "risks", risks(context, total, largestPct, largestChainPct, stablePct, defiPct, idleStableUsd, benchmarkApy, coreMajorAsset),
                "opportunities", opportunities(total, idleStableUsd, stablePct, defiPct, benchmarkApy),
                "actions", actions(context, total, largestPct, stablePct, defiPct, idleStableUsd, benchmarkApy, coreMajorAsset),
                "caveat", SAFE_CAVEAT);
    }

    private List<Map<String, Object>> risks(
            PortfolioContext context,
            double total,
            double largestPct,
            double largestChainPct,
            double stablePct,
            double defiPct,
            double idleStableUsd,
            double benchmarkApy,
            boolean coreMajorAsset) {
        List<Map<String, Object>> risks = new ArrayList<>();
        double largestUsd = total * largestPct / 100.0d;
        if (hasActionableAssetConcentration(largestPct, coreMajorAsset)) {
            risks.add(risk(
                    coreMajorAsset ? "MEDIUM" : "HIGH",
                    coreMajorAsset ? "Core asset size" : "Asset concentration",
                    assetLabel(context.largestPositionSymbol()) + " is " + pct(largestPct) + " of the portfolio, about "
                            + money(largestUsd) + ". "
                            + assetConcentrationDetail(largestUsd, coreMajorAsset),
                    largestUsd));
        }
        if (largestChainPct >= 60.0d) {
            ChainAllocation chain = largestChain(context);
            risks.add(risk(
                    "MEDIUM",
                    "Chain concentration",
                    chain.displayName() + " holds " + pct(largestChainPct) + " of portfolio value, about "
                            + money(total * largestChainPct / 100.0d) + ". Network-specific outages or bridge risk would dominate results.",
                    total * largestChainPct / 100.0d));
        }
        if (defiPct > 40.0d) {
            risks.add(risk(
                    "MEDIUM",
                    "DeFi exposure",
                    "DeFi positions represent " + pct(defiPct) + " of the portfolio, about "
                            + money(context.totalDefiValueUsd()) + ". Keep protocol and liquidation risk capped below 40%.",
                    context.totalDefiValueUsd()));
        }
        if (stablePct < 10.0d) {
            risks.add(risk(
                    "MEDIUM",
                    "Low stable buffer",
                    "Stablecoin allocation is only " + pct(stablePct) + ". A 20% cash buffer would require about "
                            + money(Math.max(total * 0.2d - total * stablePct / 100.0d, 0.0d)) + " more stables.",
                    Math.max(total * 0.2d - total * stablePct / 100.0d, 0.0d)));
        }
        if (idleStableUsd > 0.0d && benchmarkApy > 0.0d) {
            risks.add(risk(
                    "LOW",
                    "Idle stable drag",
                    money(idleStableUsd) + " in stablecoins appears idle. At the current conservative lending benchmark of "
                            + pct(benchmarkApy) + ", that is about " + money(monthlyYield(idleStableUsd, benchmarkApy))
                            + " per month of missed yield.",
                    idleStableUsd));
        }
        if (risks.isEmpty()) {
            risks.add(risk(
                    "LOW",
                    "No dominant risk",
                    "Largest position is " + pct(largestPct) + " and largest chain is " + pct(largestChainPct)
                            + ", both inside normal portfolio risk bands.",
                    null));
        }
        return risks.stream().limit(2).toList();
    }

    private List<Map<String, Object>> opportunities(double total, double idleStableUsd, double stablePct, double defiPct, double benchmarkApy) {
        List<Map<String, Object>> opportunities = new ArrayList<>();
        if (idleStableUsd > 0.0d && benchmarkApy > 0.0d) {
            opportunities.add(opportunity(
                    "Deploy idle stables",
                    money(idleStableUsd) + " of idle stablecoins could earn about "
                            + money(monthlyYield(idleStableUsd, benchmarkApy)) + " per month at the current conservative lending benchmark of "
                            + pct(benchmarkApy) + ".",
                    roundMoney(monthlyYield(idleStableUsd, benchmarkApy)),
                    "LOW"));
        } else if (idleStableUsd > 0.0d) {
            opportunities.add(opportunity(
                    "Review idle stables",
                    money(idleStableUsd)
                            + " of stablecoins appears idle. Keep it liquid until conservative lending venues are available.",
                    null,
                    "LOW"));
        }
        if (stablePct < 20.0d) {
            double targetUsd = Math.max(total * 0.2d - total * stablePct / 100.0d, 0.0d);
            opportunities.add(opportunity(
                    "Build cash buffer",
                    "A 20% stable buffer needs about " + money(targetUsd)
                            + " more stables, improving drawdown flexibility.",
                    null,
                    "MEDIUM"));
        }
        if (defiPct > 40.0d) {
            double trimUsd = Math.max(total * (defiPct - 40.0d) / 100.0d, 0.0d);
            opportunities.add(opportunity(
                    "Cap DeFi risk",
                    "Reducing DeFi exposure by " + money(trimUsd)
                            + " would move protocol risk back toward a 40% ceiling.",
                    null,
                    "MEDIUM"));
        }
        if (opportunities.isEmpty()) {
            opportunities.add(opportunity(
                    "Maintain structure",
                    "Stable allocation is " + pct(stablePct) + " and DeFi exposure is " + pct(defiPct)
                            + ", so the next edge is position-level thesis review rather than broad rebalancing.",
                    null,
                    "LOW"));
        }
        return opportunities.stream().limit(2).toList();
    }

    private List<Map<String, Object>> actions(
            PortfolioContext context,
            double total,
            double largestPct,
            double stablePct,
            double defiPct,
            double idleStableUsd,
            double benchmarkApy,
            boolean coreMajorAsset) {
        List<Map<String, Object>> actions = new ArrayList<>();
        if (hasActionableAssetConcentration(largestPct, coreMajorAsset)) {
            double targetPct = coreMajorAsset ? 60.0d : 25.0d;
            double trimUsd = total * (largestPct - targetPct) / 100.0d;
            actions.add(action(
                    coreMajorAsset ? "CONSIDER" : "URGENT",
                    "Trim " + money(trimUsd) + " of " + assetLabel(context.largestPositionSymbol()),
                    coreMajorAsset
                            ? "This keeps core BTC/ETH exposure from dominating the portfolio without treating it as a low-quality asset."
                            : "This brings the largest position closer to a 25% ceiling and reduces single-asset drawdown risk."));
        }
        if (stablePct < 20.0d) {
            double targetUsd = Math.max(total * 0.2d - total * stablePct / 100.0d, 0.0d);
            actions.add(action(
                    "THIS_WEEK",
                    "Add " + money(targetUsd) + " to stables",
                    "A 20% stable buffer improves liquidity without making the portfolio overly defensive."));
        }
        if (idleStableUsd > 0.0d && benchmarkApy > 0.0d) {
            actions.add(action(
                    "THIS_WEEK",
                    "Allocate " + money(idleStableUsd) + " idle stables",
                    "At the current conservative lending benchmark of " + pct(benchmarkApy)
                            + ", this can recover roughly " + money(monthlyYield(idleStableUsd, benchmarkApy)) + " per month."));
        }
        if (defiPct > 40.0d) {
            actions.add(action(
                    "CONSIDER",
                    "Reduce DeFi by " + money(total * (defiPct - 40.0d) / 100.0d),
                    "This lowers smart-contract and liquidity risk while keeping meaningful on-chain yield exposure."));
        }
        if (actions.isEmpty()) {
            actions.add(action(
                    "CONSIDER",
                    "Review top 3 positions",
                    "No urgent rebalance trigger fired, so thesis quality matters more than mechanical allocation changes."));
        }
        return actions.stream().limit(2).toList();
    }

    private Map<String, Object> risk(String severity, String title, String detail, Double impactUsd) {
        Map<String, Object> risk = new LinkedHashMap<>();
        risk.put("severity", severity);
        risk.put("title", title);
        risk.put("detail", detail);
        risk.put("impactUsd", impactUsd == null ? null : roundMoney(impactUsd));
        return risk;
    }

    private Map<String, Object> opportunity(String title, String detail, Double gainUsd, String effort) {
        Map<String, Object> opportunity = new LinkedHashMap<>();
        opportunity.put("title", title);
        opportunity.put("detail", detail);
        opportunity.put("gainUsd", gainUsd);
        opportunity.put("effort", effort);
        return opportunity;
    }

    private Map<String, Object> action(String priority, String action, String rationale) {
        return Map.of(
                "priority", priority,
                "action", action,
                "rationale", rationale);
    }

    private String oneLiner(String concentrationRisk, String symbol, double largestPct, double largestChainPct, boolean coreMajorAsset) {
        if (coreMajorAsset && largestPct >= 30.0d && largestPct < 65.0d) {
            return assetLabel(symbol) + " is core exposure, not the main risk.";
        }
        if (coreMajorAsset && largestPct >= 65.0d) {
            return assetLabel(symbol) + " is large core exposure; size it carefully.";
        }
        if (!coreMajorAsset && largestPct >= 30.0d) {
            return assetLabel(symbol) + " concentration is the primary portfolio risk.";
        }
        if (("CRITICAL".equals(concentrationRisk) || "HIGH".equals(concentrationRisk)) && largestChainPct >= 60.0d) {
            return "Chain concentration is the primary portfolio risk.";
        }
        if (largestPct < 20.0d) {
            return "Portfolio risk is balanced with no dominant position.";
        }
        return "Portfolio is workable but concentration needs monitoring.";
    }

    private String concentrationRisk(double largestPct, double largestChainPct, boolean coreMajorAsset) {
        return maxRisk(assetConcentrationRisk(largestPct, coreMajorAsset), chainConcentrationRisk(largestChainPct));
    }

    private String assetConcentrationRisk(double largestPct, boolean coreMajorAsset) {
        if (coreMajorAsset) {
            if (largestPct >= 80.0d) {
                return "HIGH";
            }
            if (largestPct >= 65.0d) {
                return "MEDIUM";
            }
            return "LOW";
        }
        if (largestPct >= 50.0d) {
            return "CRITICAL";
        }
        if (largestPct >= 30.0d) {
            return "HIGH";
        }
        if (largestPct >= 20.0d) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private String chainConcentrationRisk(double largestChainPct) {
        if (largestChainPct >= 80.0d) {
            return "CRITICAL";
        }
        if (largestChainPct >= 60.0d) {
            return "MEDIUM";
        }
        if (largestChainPct >= 45.0d) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private String maxRisk(String first, String second) {
        return riskRank(first) >= riskRank(second) ? first : second;
    }

    private int riskRank(String risk) {
        return switch (risk) {
            case "CRITICAL" -> 4;
            case "HIGH" -> 3;
            case "MEDIUM" -> 2;
            default -> 1;
        };
    }

    private String healthLabel(int score) {
        if (score >= 80) {
            return "STRONG";
        }
        if (score >= 65) {
            return "GOOD";
        }
        if (score >= 45) {
            return "MODERATE";
        }
        if (score >= 25) {
            return "WEAK";
        }
        return "CRITICAL";
    }

    private int liquidityScore(double stablePct, double defiPct) {
        int score = 100;
        if (stablePct < 20.0d) {
            score -= (int) Math.round((20.0d - stablePct) * 2.0d);
        }
        if (defiPct > 40.0d) {
            score -= (int) Math.round((defiPct - 40.0d) * 1.5d);
        }
        return clampToInt(score, 0, 100);
    }

    private int yieldEfficiency(double total, double idleStableUsd) {
        if (total <= 0.0d) {
            return 0;
        }
        return clampToInt(100 - (int) Math.round(idleStableUsd / total * 100.0d * 2.0d), 0, 100);
    }

    private int diversificationScore(double largestPct, double largestChainPct, boolean coreMajorAsset) {
        double assetLoad = coreMajorAsset
                ? Math.max(largestPct - 45.0d, 0.0d) * 0.8d
                : largestPct * 1.2d;
        return clampToInt(100 - (int) Math.round(assetLoad + Math.max(largestChainPct - 35.0d, 0.0d) * 0.6d), 0, 100);
    }

    private int concentrationPenalty(double largestPct, boolean coreMajorAsset) {
        if (coreMajorAsset) {
            return largestPct >= 80.0d ? 18 : largestPct >= 65.0d ? 8 : 0;
        }
        return largestPct >= 50.0d ? 32 : largestPct >= 30.0d ? 22 : largestPct >= 20.0d ? 10 : 0;
    }

    private boolean hasActionableAssetConcentration(double largestPct, boolean coreMajorAsset) {
        return coreMajorAsset ? largestPct >= 70.0d : largestPct >= 30.0d;
    }

    private String assetConcentrationDetail(double largestUsd, boolean coreMajorAsset) {
        if (coreMajorAsset) {
            return "BTC/ETH exposure is core portfolio exposure, so the risk is sizing, not asset quality.";
        }
        return "A 20% drawdown there would hit net value by roughly " + money(largestUsd * 0.2d) + ".";
    }

    private boolean isCoreMajorAsset(String symbol) {
        String normalized = symbol == null
                ? ""
                : symbol.trim().toUpperCase(Locale.US).replaceAll("[^A-Z0-9]", "");
        return CORE_MAJOR_SYMBOLS.contains(normalized);
    }

    private String assetLabel(String symbol) {
        return symbol == null || symbol.isBlank() ? "Largest asset" : symbol;
    }

    private double largestChainPct(PortfolioContext context) {
        if (context.totalValueUsd() <= 0.0d) {
            return 0.0d;
        }
        return context.chainAllocations().stream()
                .map(ChainAllocation::valueUsd)
                .mapToDouble(value -> value == null ? 0.0d : value.doubleValue())
                .max()
                .orElse(0.0d) / context.totalValueUsd() * 100.0d;
    }

    private ChainAllocation largestChain(PortfolioContext context) {
        return context.chainAllocations().stream()
                .max(Comparator.comparing(allocation -> allocation.valueUsd() == null
                        ? java.math.BigDecimal.ZERO
                        : allocation.valueUsd()))
                .orElse(new ChainAllocation("unknown", "Unknown", null));
    }

    private double monthlyYield(double valueUsd, double apy) {
        return valueUsd * apy / 100.0d / 12.0d;
    }

    private double yieldBenchmarkApy(StableYieldMarket market) {
        return market == null || market.benchmarkApy() == null ? 0.0d : market.benchmarkApy();
    }

    private String pct(double value) {
        return String.format(Locale.US, "%.1f%%", value);
    }

    private String money(double value) {
        return USD.format(roundMoney(value));
    }

    private double roundMoney(double value) {
        return Math.round(value * 100.0d) / 100.0d;
    }

    private int clampToInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
