"use client";

import { useMemo } from "react";
import type {
  BenchmarkData,
  DefiPositionResponse,
  PortfolioHistoryPoint,
  PortfolioHistoryResponse,
  PortfolioMetricsResponse,
  WalletRecord,
} from "@/types/portfolio";

type Props = {
  wallets: WalletRecord[];
  chains: string[];
  portfolioTotalUsd: number;
  defiExposureUsd: number;
  defiPositionCount: number;
  defiLoading: boolean;
  defiPositions?: DefiPositionResponse[];
  protocolValues?: Record<string, number>;
  protocolPositionCounts?: Record<string, number>;
  metrics: PortfolioMetricsResponse | null;
  history: PortfolioHistoryResponse | null;
  benchmarks: BenchmarkData | null;
  metricsLoading: boolean;
  historyLoading: boolean;
  benchmarkLoading: boolean;
  metricsPartial: boolean;
  historyPartial: boolean;
  benchmarkPartial: boolean;
  refreshing: boolean;
  lastRefresh: string | null;
  onRefresh: () => void;
};

type Tone = "green" | "amber" | "red" | "blue" | "muted";

type DailyReturn = {
  date: string;
  valueUsd: number;
  returnDecimal: number;
};

type TailRisk = {
  sampleSize: number;
  varReturn: number | null;
  varLossUsd: number | null;
  expectedShortfallReturn: number | null;
  expectedShortfallLossUsd: number | null;
  worstReturn: number | null;
  worstLossUsd: number | null;
};

type BenchmarkStats = {
  label: "BTC" | "SOL";
  beta: number | null;
  correlation: number | null;
  sampleSize: number;
};

type ProtocolData = {
  totalUsd: number;
  positionCount: number;
  topProtocol: {
    name: string;
    valueUsd: number;
    positionCount: number;
    shareOfProtocolUsd: number;
  } | null;
};

type RiskModule = {
  title: string;
  status: string;
  value: string;
  detail: string;
  footnote: string;
  tone: Tone;
  rows: Array<[string, string]>;
};

export function RiskEngineView({
  wallets,
  chains,
  portfolioTotalUsd,
  defiExposureUsd,
  defiPositionCount,
  defiLoading,
  defiPositions = [],
  protocolValues = {},
  protocolPositionCounts = {},
  metrics,
  history,
  benchmarks,
  metricsLoading,
  historyLoading,
  benchmarkLoading,
  metricsPartial,
  historyPartial,
  benchmarkPartial,
  refreshing,
  lastRefresh,
  onRefresh,
}: Props) {
  const historyPoints = useMemo(() => normalizedHistoryPoints(history?.points ?? []), [history]);
  const returns = useMemo(() => dailyReturns(historyPoints), [historyPoints]);
  const protocolData = useMemo(
    () => buildProtocolData(defiPositions, protocolValues, protocolPositionCounts),
    [defiPositions, protocolPositionCounts, protocolValues],
  );
  const totalUsd = portfolioTotalUsd > 0 ? portfolioTotalUsd : metrics?.totalUsd ?? 0;
  const protocolExposureUsd = Math.max(metrics?.defiExposureUsd ?? 0, defiExposureUsd, protocolData.totalUsd);
  const protocolExposurePct = pct(protocolExposureUsd, totalUsd);
  const positionCount = Math.max(metrics?.defiPositionCount ?? 0, defiPositionCount, protocolData.positionCount);
  const tailRisk = useMemo(() => buildTailRisk(returns, totalUsd), [returns, totalUsd]);
  const benchmarkStats = useMemo(() => buildBenchmarkStats(returns, benchmarks), [returns, benchmarks]);
  const posture = useMemo(
    () => buildRiskPosture(metrics, protocolExposurePct, tailRisk),
    [metrics, protocolExposurePct, tailRisk],
  );
  const modules = useMemo(
    () => buildRiskModules({
      metrics,
      totalUsd,
      protocolExposureUsd,
      protocolExposurePct,
      positionCount,
      tailRisk,
      benchmarkStats,
      protocolData,
    }),
    [benchmarkStats, metrics, positionCount, protocolData, protocolExposurePct, protocolExposureUsd, tailRisk, totalUsd],
  );
  const scenarios = useMemo(
    () => buildStressScenarios(metrics, totalUsd, protocolExposureUsd),
    [metrics, protocolExposureUsd, totalUsd],
  );
  const partialInputs = metricsPartial || historyPartial || benchmarkPartial;
  const syncing = metricsLoading || historyLoading || benchmarkLoading || defiLoading || refreshing;
  const historyLabel = historyPoints.length > 0
    ? `${historyPoints.length}D HISTORY`
    : syncing
      ? "SYNCING"
      : "HISTORY BUILDING";

  return (
    <section className="s-risk-page">
      <header className="s-risk-hero">
        <div className="s-risk-hero-copy">
          <p className="s-kicker">RISK ENGINE</p>
          <h2>Risk metrics command center</h2>
          <p>
            Deterministic portfolio risk from concentration, stables, protocol exposure, 30D returns,
            benchmark beta, tail loss, and stress scenarios.
          </p>
        </div>
        <div className={`s-risk-command-card is-${posture.tone}`}>
          <span>RISK POSTURE</span>
          <strong className="mono">{posture.score}</strong>
          <p>{posture.label}</p>
          <small>{posture.detail}</small>
        </div>
      </header>

      <div className="s-risk-status-strip">
        <StatusCell label="Scope" value={`${wallets.length} wallets`} detail={`${chains.length} chains selected`} />
        <StatusCell label="Sample" value={historyLabel} detail={`${returns.length} daily returns`} />
        <StatusCell label="Inputs" value={partialInputs ? "PARTIAL" : syncing ? "SYNCING" : "LIVE"} detail={inputDetail(partialInputs, syncing)} />
        <StatusCell label="DeFi Surface" value={formatPct(protocolExposurePct)} detail={`${formatCurrency(protocolExposureUsd)} / ${positionCount} positions`} />
        <StatusAction
          label="Refresh"
          value={refreshing ? "SYNCING..." : "SYNC LIVE"}
          detail={lastRefresh ? `Last ${formatShortDateTime(lastRefresh)}` : "No manual sync yet"}
          disabled={refreshing}
          onClick={onRefresh}
        />
      </div>

      {partialInputs ? (
        <div className="s-risk-notice">
          <strong>Partial live inputs.</strong>
          <p>Available modules stay visible while missing market or history inputs are rebuilding.</p>
        </div>
      ) : null}

      <div className="s-risk-kpi-grid">
        <RiskKpi label="Portfolio Value" value={formatCurrency(totalUsd)} detail="Current analysis base" tone="muted" />
        <RiskKpi
          label="1D VaR 95"
          value={tailRisk.varLossUsd == null ? "ACCUMULATING" : formatCurrency(tailRisk.varLossUsd)}
          detail={tailRisk.varReturn == null ? "History sample still building" : `${formatReturnPct(tailRisk.varReturn)} threshold`}
          tone={tailRiskTone(tailRisk.varReturn)}
        />
        <RiskKpi
          label="Expected Shortfall"
          value={tailRisk.expectedShortfallLossUsd == null ? "ACCUMULATING" : formatCurrency(tailRisk.expectedShortfallLossUsd)}
          detail={tailRisk.expectedShortfallReturn == null ? "Tail sample still building" : `${formatReturnPct(tailRisk.expectedShortfallReturn)} average tail day`}
          tone={tailRiskTone(tailRisk.expectedShortfallReturn)}
        />
        <RiskKpi
          label="Benchmark Beta"
          value={benchmarkValue(benchmarkStats)}
          detail={benchmarkDetail(benchmarkStats)}
          tone={benchmarkTone(benchmarkStats)}
        />
        <RiskKpi
          label="Protocol Exposure"
          value={formatCurrency(protocolExposureUsd)}
          detail={`${formatPct(protocolExposurePct)} of portfolio`}
          tone={protocolTone(protocolExposurePct)}
        />
      </div>

      <div className="s-risk-grid">
        <section className="s-panel s-risk-panel s-risk-side-panel">
          <div className="s-panel-hd">
            <span>CURRENT RISK READOUT</span>
            <span className="s-badge">{metricsLoading ? "SYNCING" : metrics ? "LIVE INPUTS" : "PARTIAL"}</span>
          </div>
          <div className="s-risk-table">
            <RiskRow
              label="Concentration"
              value={metrics ? `${metrics.concentrationAsset} ${formatPct(metrics.concentrationPct)}` : "PARTIAL"}
              tone={riskTone(metrics?.concentrationRisk)}
              meaning={metrics ? concentrationMeaning(metrics.concentrationRisk) : "Concentration module is waiting for asset-level metrics."}
              inputs={metrics ? `${formatCurrency(metrics.concentrationUsd)} largest asset / ${formatCurrency(totalUsd)} portfolio` : `${formatCurrency(totalUsd)} portfolio base available`}
            />
            <RiskRow
              label="Stablecoin Buffer"
              value={metrics ? formatPct(metrics.stableAllocationPct) : "PARTIAL"}
              tone={metrics ? stableTone(metrics.stableAllocationPct) : "muted"}
              meaning={metrics ? stableMeaning(metrics.stableAllocationPct) : "Stable allocation is not ready yet."}
              inputs={metrics ? `${formatCurrency(metrics.stableUsd)} stables, ${formatCurrency(metrics.idleStableUsd)} idle` : "Stable module pending"}
            />
            <RiskRow
              label="DeFi Exposure"
              value={formatPct(protocolExposurePct)}
              tone={protocolTone(protocolExposurePct)}
              meaning="Protocol risk surface across vaults, lending, staking, LPs, and reward wrappers."
              inputs={`${formatCurrency(protocolExposureUsd)} protocol value, ${positionCount} positions`}
            />
            <RiskRow
              label="Sharpe 30D"
              value={metricNumber(metrics?.sharpe30d)}
              tone={ratioTone(metrics?.sharpe30d)}
              meaning="Return earned per unit of daily volatility."
              inputs={metrics ? `${formatNullablePct(metrics.averageDailyReturnPct30d)} avg daily return, ${formatNullablePct(metrics.dailyVolatilityPct30d)} daily volatility` : `${returns.length} return observations`}
            />
            <RiskRow
              label="Sortino 30D"
              value={metricNumber(metrics?.sortino30d)}
              tone={ratioTone(metrics?.sortino30d)}
              meaning="Return quality after penalising downside days only."
              inputs={metrics ? `${formatNullablePct(metrics.downsideDeviationPct30d)} downside deviation` : "Downside sample pending"}
            />
            <RiskRow
              label="Max Drawdown 30D"
              value={metrics?.maxDrawdownPct30d == null ? "ACCUMULATING" : formatPct(metrics.maxDrawdownPct30d)}
              tone={drawdownTone(metrics?.maxDrawdownPct30d)}
              meaning="Worst peak-to-trough decline inside the current history window."
              inputs={drawdownRange(metrics)}
            />
          </div>
        </section>

        <section className="s-panel s-risk-panel">
          <div className="s-panel-hd">
            <span>TAIL RISK TAPE</span>
            <span className="s-badge">HISTORICAL 30D</span>
          </div>
          <div className="s-risk-tape">
            <TapeRow label="Worst Day" value={tailRisk.worstReturn == null ? "ACCUMULATING" : formatReturnPct(tailRisk.worstReturn)} detail={tailRisk.worstLossUsd == null ? "History sample still building" : formatCurrency(tailRisk.worstLossUsd)} tone={tailRiskTone(tailRisk.worstReturn)} />
            <TapeRow label="VaR 95" value={tailRisk.varReturn == null ? "ACCUMULATING" : formatReturnPct(tailRisk.varReturn)} detail={tailRisk.varLossUsd == null ? "Threshold pending" : formatCurrency(tailRisk.varLossUsd)} tone={tailRiskTone(tailRisk.varReturn)} />
            <TapeRow label="Expected Shortfall" value={tailRisk.expectedShortfallReturn == null ? "ACCUMULATING" : formatReturnPct(tailRisk.expectedShortfallReturn)} detail={tailRisk.expectedShortfallLossUsd == null ? "Tail average pending" : formatCurrency(tailRisk.expectedShortfallLossUsd)} tone={tailRiskTone(tailRisk.expectedShortfallReturn)} />
            <TapeRow label="Return Sample" value={`${tailRisk.sampleSize}`} detail="Daily observations" tone="muted" />
          </div>

          <div className="s-risk-benchmark-block">
            <span>BENCHMARK FACTOR LOAD</span>
            <BenchmarkBar stats={benchmarkStats.bitcoin} />
            <BenchmarkBar stats={benchmarkStats.solana} />
          </div>
        </section>
      </div>

      <section className="s-panel s-risk-panel">
        <div className="s-panel-hd">
          <span>IMPLEMENTED RISK MODULES</span>
          <span className="s-badge">{modules.length} MODULES</span>
        </div>
        <div className="s-risk-module-grid">
          {modules.map((module) => (
            <RiskModuleCard key={module.title} module={module} />
          ))}
        </div>
      </section>

      <section className="s-panel s-risk-panel">
        <div className="s-panel-hd">
          <span>SCENARIO STRESS DECK</span>
          <span className="s-badge">DETERMINISTIC SHOCKS</span>
        </div>
        <div className="s-risk-scenario-grid">
          {scenarios.map((scenario) => (
            <article key={scenario.title} className={`s-risk-scenario-card is-${scenario.tone}`}>
              <span>{scenario.shock}</span>
              <strong>{scenario.title}</strong>
              <p>{scenario.detail}</p>
              <small className="mono">{scenario.loss}</small>
            </article>
          ))}
        </div>
      </section>
    </section>
  );
}

function RiskKpi({ label, value, detail, tone }: { label: string; value: string; detail: string; tone: Tone }) {
  return (
    <article className={`s-risk-kpi is-${tone}`}>
      <span>{label}</span>
      <strong className="mono">{value}</strong>
      <p>{detail}</p>
    </article>
  );
}

function StatusCell({ label, value, detail }: { label: string; value: string; detail: string }) {
  return (
    <div className="s-risk-status-cell">
      <span>{label}</span>
      <strong className="mono">{value}</strong>
      <p>{detail}</p>
    </div>
  );
}

function StatusAction({
  label,
  value,
  detail,
  disabled,
  onClick,
}: {
  label: string;
  value: string;
  detail: string;
  disabled: boolean;
  onClick: () => void;
}) {
  return (
    <div className="s-risk-status-cell s-risk-status-action">
      <span>{label}</span>
      <button className="s-risk-sync-btn mono" type="button" disabled={disabled} onClick={onClick}>
        {value}
      </button>
      <p>{detail}</p>
    </div>
  );
}

function RiskRow({
  label,
  value,
  meaning,
  inputs,
  tone,
}: {
  label: string;
  value: string;
  meaning: string;
  inputs: string;
  tone: Tone;
}) {
  return (
    <article className={`s-risk-row is-${tone}`}>
      <div>
        <span>{label}</span>
        <strong className="mono">{value}</strong>
      </div>
      <p>{meaning}</p>
      <small>{inputs}</small>
    </article>
  );
}

function TapeRow({ label, value, detail, tone }: { label: string; value: string; detail: string; tone: Tone }) {
  return (
    <article className={`s-risk-tape-row is-${tone}`}>
      <span>{label}</span>
      <strong className="mono">{value}</strong>
      <p>{detail}</p>
    </article>
  );
}

function BenchmarkBar({ stats }: { stats: BenchmarkStats }) {
  const beta = stats.beta == null ? 0 : clamp(Math.abs(stats.beta), 0, 2);
  return (
    <div className="s-risk-benchmark-row">
      <div>
        <strong>{stats.label}</strong>
        <span>{stats.sampleSize > 0 ? `${stats.sampleSize} aligned days` : "accumulating"}</span>
      </div>
      <div className="s-risk-benchmark-track">
        <span style={{ width: `${Math.max(4, (beta / 2) * 100)}%` }} />
      </div>
      <p className="mono">
        {stats.beta == null ? "N/A" : `${stats.beta.toFixed(2)} beta`} / {stats.correlation == null ? "N/A" : `${stats.correlation.toFixed(2)} corr`}
      </p>
    </div>
  );
}

function RiskModuleCard({ module }: { module: RiskModule }) {
  return (
    <article className={`s-risk-module-card is-${module.tone}`}>
      <div className="s-risk-module-head">
        <span>{module.status}</span>
        <strong>{module.title}</strong>
      </div>
      <div className="s-risk-module-value mono">{module.value}</div>
      <p>{module.detail}</p>
      <dl>
        {module.rows.map(([term, detail]) => (
          <div key={term}>
            <dt>{term}</dt>
            <dd>{detail}</dd>
          </div>
        ))}
      </dl>
      <small>{module.footnote}</small>
    </article>
  );
}

function buildRiskModules({
  metrics,
  totalUsd,
  protocolExposureUsd,
  protocolExposurePct,
  positionCount,
  tailRisk,
  benchmarkStats,
  protocolData,
}: {
  metrics: PortfolioMetricsResponse | null;
  totalUsd: number;
  protocolExposureUsd: number;
  protocolExposurePct: number;
  positionCount: number;
  tailRisk: TailRisk;
  benchmarkStats: { bitcoin: BenchmarkStats; solana: BenchmarkStats };
  protocolData: ProtocolData;
}): RiskModule[] {
  const primaryBenchmark = pickPrimaryBenchmark(benchmarkStats);
  const topProtocol = protocolData.topProtocol;
  const topProtocolPortfolioShare = topProtocol ? pct(topProtocol.valueUsd, totalUsd) : 0;

  return [
    {
      title: "Value at Risk",
      status: tailRisk.varLossUsd == null ? "BUILDING" : "LIVE",
      value: tailRisk.varLossUsd == null ? "ACCUMULATING" : formatCurrency(tailRisk.varLossUsd),
      detail: tailRisk.varReturn == null
        ? "Historical loss threshold appears after enough daily portfolio returns."
        : `95% historical one-day loss threshold is ${formatReturnPct(tailRisk.varReturn)} on the current portfolio base.`,
      footnote: "Historical VaR, not a guarantee.",
      tone: tailRiskTone(tailRisk.varReturn),
      rows: [
        ["Confidence", "95%"],
        ["Sample", `${tailRisk.sampleSize} returns`],
        ["Portfolio base", formatCurrency(totalUsd)],
      ],
    },
    {
      title: "Expected Shortfall",
      status: tailRisk.expectedShortfallLossUsd == null ? "BUILDING" : "LIVE",
      value: tailRisk.expectedShortfallLossUsd == null ? "ACCUMULATING" : formatCurrency(tailRisk.expectedShortfallLossUsd),
      detail: tailRisk.expectedShortfallReturn == null
        ? "Tail average appears after the bad-day sample is large enough."
        : `Average loss inside the VaR tail is ${formatReturnPct(tailRisk.expectedShortfallReturn)}.`,
      footnote: "Shows tail severity after VaR is breached.",
      tone: tailRiskTone(tailRisk.expectedShortfallReturn),
      rows: [
        ["Worst day", tailRisk.worstReturn == null ? "N/A" : formatReturnPct(tailRisk.worstReturn)],
        ["Worst loss", tailRisk.worstLossUsd == null ? "N/A" : formatCurrency(tailRisk.worstLossUsd)],
        ["Tail loss", tailRisk.expectedShortfallLossUsd == null ? "N/A" : formatCurrency(tailRisk.expectedShortfallLossUsd)],
      ],
    },
    {
      title: "BTC / SOL Beta",
      status: primaryBenchmark.beta == null ? "BUILDING" : "LIVE",
      value: primaryBenchmark.beta == null ? "ACCUMULATING" : `${primaryBenchmark.label} ${primaryBenchmark.beta.toFixed(2)}`,
      detail: primaryBenchmark.beta == null
        ? "Benchmark beta appears after portfolio history and benchmark history align."
        : `Highest current factor load is against ${primaryBenchmark.label}.`,
      footnote: "Beta above 1.00 means amplified benchmark movement.",
      tone: benchmarkTone(benchmarkStats),
      rows: [
        ["BTC beta", benchmarkStats.bitcoin.beta == null ? "N/A" : benchmarkStats.bitcoin.beta.toFixed(2)],
        ["SOL beta", benchmarkStats.solana.beta == null ? "N/A" : benchmarkStats.solana.beta.toFixed(2)],
        ["Aligned days", `${Math.max(benchmarkStats.bitcoin.sampleSize, benchmarkStats.solana.sampleSize)}`],
      ],
    },
    {
      title: "Correlation Matrix",
      status: primaryBenchmark.correlation == null ? "BUILDING" : "LIVE",
      value: primaryBenchmark.correlation == null ? "ACCUMULATING" : `${primaryBenchmark.label} ${primaryBenchmark.correlation.toFixed(2)}`,
      detail: primaryBenchmark.correlation == null
        ? "Correlation appears after aligned daily benchmark returns are available."
        : `Portfolio currently moves closest to ${primaryBenchmark.label}.`,
      footnote: "Correlation measures direction, not loss size.",
      tone: correlationTone(primaryBenchmark.correlation),
      rows: [
        ["BTC corr", benchmarkStats.bitcoin.correlation == null ? "N/A" : benchmarkStats.bitcoin.correlation.toFixed(2)],
        ["SOL corr", benchmarkStats.solana.correlation == null ? "N/A" : benchmarkStats.solana.correlation.toFixed(2)],
        ["Sample", `${Math.max(benchmarkStats.bitcoin.sampleSize, benchmarkStats.solana.sampleSize)} days`],
      ],
    },
    {
      title: "Liquidity Stress",
      status: protocolExposureUsd > 0 ? "LIVE" : "LOW SURFACE",
      value: formatCurrency(protocolExposureUsd),
      detail: protocolExposureUsd > 0
        ? `${formatPct(protocolExposurePct)} of the portfolio depends on protocol exits, withdrawals, or vault accounting.`
        : "No material protocol exit surface is visible in the current portfolio.",
      footnote: "Pool depth and withdrawal queues are not assumed.",
      tone: protocolTone(protocolExposurePct),
      rows: [
        ["Protocol value", formatCurrency(protocolExposureUsd)],
        ["Portfolio share", formatPct(protocolExposurePct)],
        ["Positions", `${positionCount}`],
      ],
    },
    {
      title: "Stablecoin Risk",
      status: metrics ? "LIVE" : "BUILDING",
      value: metrics ? formatPct(metrics.stableAllocationPct) : "ACCUMULATING",
      detail: metrics
        ? `${formatCurrency(metrics.stableUsd)} sits in stablecoins, with ${formatCurrency(metrics.idleStableUsd)} currently idle.`
        : "Stablecoin exposure appears when portfolio metrics are ready.",
      footnote: "Issuer and wrapper mapping can refine this further.",
      tone: metrics ? stableTone(metrics.stableAllocationPct) : "muted",
      rows: [
        ["Stable value", metrics ? formatCurrency(metrics.stableUsd) : "N/A"],
        ["Idle stables", metrics ? formatCurrency(metrics.idleStableUsd) : "N/A"],
        ["Monthly drag", metrics ? formatCurrency(metrics.monthlyOpportunityCostUsd) : "N/A"],
      ],
    },
    {
      title: "Protocol Concentration",
      status: topProtocol ? "LIVE" : protocolExposureUsd > 0 ? "PARTIAL" : "LOW SURFACE",
      value: topProtocol ? topProtocol.name : protocolExposureUsd > 0 ? formatCurrency(protocolExposureUsd) : "NONE",
      detail: topProtocol
        ? `${topProtocol.name} is ${formatPct(topProtocol.shareOfProtocolUsd)} of visible protocol exposure.`
        : protocolExposureUsd > 0
          ? "Protocol exposure exists, but per-protocol values are not fully mapped in this view."
          : "No material protocol concentration is visible.",
      footnote: "Uses protocol names from current DeFi positions.",
      tone: topProtocol ? protocolTone(topProtocolPortfolioShare) : protocolTone(protocolExposurePct),
      rows: [
        ["Top protocol", topProtocol?.name ?? "N/A"],
        ["Top value", topProtocol ? formatCurrency(topProtocol.valueUsd) : "N/A"],
        ["Top share", topProtocol ? formatPct(topProtocolPortfolioShare) : "N/A"],
      ],
    },
    {
      title: "Stress Scenarios",
      status: "LIVE",
      value: largestScenarioLoss(metrics, protocolExposureUsd),
      detail: "Runs deterministic shocks against current largest asset, protocol surface, and stablecoin base.",
      footnote: "Scenario assumptions are visible in the stress deck below.",
      tone: "blue",
      rows: [
        ["Largest asset -15%", metrics ? formatCurrency(metrics.concentrationUsd * 0.15) : "N/A"],
        ["Protocol -20%", formatCurrency(protocolExposureUsd * 0.2)],
        ["Stable depeg -2%", metrics ? formatCurrency(metrics.stableUsd * 0.02) : "N/A"],
      ],
    },
  ];
}

function buildStressScenarios(metrics: PortfolioMetricsResponse | null, totalUsd: number, protocolExposureUsd: number) {
  const concentrationLoss = metrics ? metrics.concentrationUsd * 0.15 : 0;
  const protocolLoss = protocolExposureUsd * 0.2;
  const stableLoss = metrics ? metrics.stableUsd * 0.02 : 0;
  const drawdownReplayLoss = metrics?.maxDrawdownPct30d == null
    ? null
    : Math.abs(metrics.maxDrawdownPct30d) / 100 * totalUsd;

  return [
    {
      title: "Largest Asset Shock",
      shock: "-15%",
      detail: metrics ? `${metrics.concentrationAsset} shock against the largest visible position.` : "Largest asset input is still building.",
      loss: metrics ? formatCurrency(concentrationLoss) : "ACCUMULATING",
      tone: scenarioTone(concentrationLoss, totalUsd),
    },
    {
      title: "Protocol Haircut",
      shock: "-20%",
      detail: "Applies a direct protocol-side haircut to visible DeFi exposure.",
      loss: formatCurrency(protocolLoss),
      tone: scenarioTone(protocolLoss, totalUsd),
    },
    {
      title: "Stablecoin Depeg",
      shock: "-2%",
      detail: "Applies a mild depeg shock to visible stablecoin value.",
      loss: metrics ? formatCurrency(stableLoss) : "ACCUMULATING",
      tone: scenarioTone(stableLoss, totalUsd),
    },
    {
      title: "Drawdown Replay",
      shock: "30D",
      detail: "Replays the worst recent peak-to-trough portfolio decline.",
      loss: drawdownReplayLoss == null ? "ACCUMULATING" : formatCurrency(drawdownReplayLoss),
      tone: scenarioTone(drawdownReplayLoss ?? 0, totalUsd),
    },
  ];
}

function buildRiskPosture(
  metrics: PortfolioMetricsResponse | null,
  protocolExposurePct: number,
  tailRisk: TailRisk,
): { score: string; label: string; detail: string; tone: Tone } {
  let score = 100;

  if (metrics?.concentrationRisk === "CRITICAL") score -= 30;
  else if (metrics?.concentrationRisk === "HIGH") score -= 18;
  else if (metrics?.concentrationRisk === "MEDIUM") score -= 8;

  if (metrics) {
    if (metrics.stableAllocationPct < 10) score -= 10;
    else if (metrics.stableAllocationPct < 20) score -= 5;
    else if (metrics.stableAllocationPct > 60) score -= 12;
    else if (metrics.stableAllocationPct > 40) score -= 6;

    if (metrics.maxDrawdownPct30d != null) {
      const drawdown = metrics.maxDrawdownPct30d;
      if (drawdown <= -30) score -= 20;
      else if (drawdown <= -20) score -= 12;
      else if (drawdown <= -10) score -= 6;
    }
  }

  if (protocolExposurePct > 60) score -= 25;
  else if (protocolExposurePct > 40) score -= 16;
  else if (protocolExposurePct > 20) score -= 8;

  if (tailRisk.varReturn != null) {
    if (tailRisk.varReturn <= -0.1) score -= 14;
    else if (tailRisk.varReturn <= -0.05) score -= 8;
    else if (tailRisk.varReturn <= -0.02) score -= 4;
  }

  const bounded = Math.round(clamp(score, 0, 100));
  const scoreDetail = "Composite score from concentration, stables, drawdown, protocol exposure, and VaR.";
  if (bounded < 45) {
    return { score: `${bounded}/100`, label: "ELEVATED RISK", detail: scoreDetail, tone: "red" };
  }
  if (bounded < 65) {
    return { score: `${bounded}/100`, label: "WATCHLIST", detail: scoreDetail, tone: "amber" };
  }
  if (bounded < 82) {
    return { score: `${bounded}/100`, label: "CONTROLLED", detail: scoreDetail, tone: "blue" };
  }
  return { score: `${bounded}/100`, label: "RESILIENT", detail: scoreDetail, tone: "green" };
}

function buildTailRisk(returns: DailyReturn[], totalUsd: number): TailRisk {
  if (returns.length < 7 || totalUsd <= 0) {
    return {
      sampleSize: returns.length,
      varReturn: null,
      varLossUsd: null,
      expectedShortfallReturn: null,
      expectedShortfallLossUsd: null,
      worstReturn: null,
      worstLossUsd: null,
    };
  }

  const sorted = returns.map((point) => point.returnDecimal).sort((left, right) => left - right);
  const varIndex = Math.max(0, Math.floor(sorted.length * 0.05));
  const varReturn = Math.min(sorted[varIndex] ?? 0, 0);
  const tail = sorted.filter((value) => value <= varReturn);
  const expectedShortfallReturn = tail.length === 0 ? varReturn : mean(tail);
  const worstReturn = Math.min(sorted[0] ?? 0, 0);

  return {
    sampleSize: returns.length,
    varReturn,
    varLossUsd: lossUsd(varReturn, totalUsd),
    expectedShortfallReturn,
    expectedShortfallLossUsd: lossUsd(expectedShortfallReturn, totalUsd),
    worstReturn,
    worstLossUsd: lossUsd(worstReturn, totalUsd),
  };
}

function buildBenchmarkStats(
  portfolioReturns: DailyReturn[],
  benchmarks: BenchmarkData | null,
): { bitcoin: BenchmarkStats; solana: BenchmarkStats } {
  return {
    bitcoin: benchmarkStats("BTC", portfolioReturns, benchmarks?.bitcoin ?? []),
    solana: benchmarkStats("SOL", portfolioReturns, benchmarks?.solana ?? []),
  };
}

function benchmarkStats(
  label: "BTC" | "SOL",
  portfolioReturns: DailyReturn[],
  benchmarkPoints: BenchmarkData["bitcoin"],
): BenchmarkStats {
  const benchmarkReturns = benchmarkDailyReturns(benchmarkPoints);
  const benchmarkByDate = new Map(benchmarkReturns.map((point) => [point.date, point.returnDecimal]));
  const pairs = portfolioReturns
    .map((point) => ({ portfolio: point.returnDecimal, benchmark: benchmarkByDate.get(point.date) }))
    .filter((point): point is { portfolio: number; benchmark: number } => point.benchmark != null);

  if (pairs.length < 5) {
    return { label, beta: null, correlation: null, sampleSize: pairs.length };
  }

  const portfolio = pairs.map((point) => point.portfolio);
  const benchmark = pairs.map((point) => point.benchmark);
  const benchmarkVariance = variance(benchmark);
  const covarianceValue = covariance(portfolio, benchmark);
  const beta = benchmarkVariance === 0 ? null : covarianceValue / benchmarkVariance;
  const denominator = stddev(portfolio) * stddev(benchmark);
  const correlation = denominator === 0 ? null : covarianceValue / denominator;

  return {
    label,
    beta,
    correlation,
    sampleSize: pairs.length,
  };
}

function buildProtocolData(
  positions: DefiPositionResponse[],
  protocolValues: Record<string, number>,
  protocolPositionCounts: Record<string, number>,
): ProtocolData {
  const groups = new Map<string, { name: string; valueUsd: number; positionCount: number }>();

  for (const [protocolName, valueUsd] of Object.entries(protocolValues)) {
    if (valueUsd <= 0) {
      continue;
    }
    const name = readableProtocol(protocolName);
    groups.set(name, {
      name,
      valueUsd,
      positionCount: protocolPositionCounts[protocolName] ?? 0,
    });
  }

  for (const position of positions) {
    const valueUsd = Math.max(position.supplyUsd ?? 0, Math.abs(position.netUsd ?? 0));
    if (valueUsd <= 0) {
      continue;
    }
    const name = readableProtocol(position.protocolName || position.protocolKey);
    const existing = groups.get(name) ?? { name, valueUsd: 0, positionCount: 0 };
    existing.valueUsd += valueUsd;
    existing.positionCount += 1;
    groups.set(name, existing);
  }

  const rows = [...groups.values()].sort((left, right) => right.valueUsd - left.valueUsd);
  const totalUsd = rows.reduce((sum, row) => sum + row.valueUsd, 0);
  const top = rows[0] ?? null;

  return {
    totalUsd,
    positionCount: positions.length,
    topProtocol: top
      ? {
          name: top.name,
          valueUsd: top.valueUsd,
          positionCount: top.positionCount,
          shareOfProtocolUsd: pct(top.valueUsd, totalUsd),
        }
      : null,
  };
}

function normalizedHistoryPoints(points: PortfolioHistoryPoint[]): PortfolioHistoryPoint[] {
  return points
    .filter((point) => point.totalUsd > 0)
    .slice()
    .sort((left, right) => left.localDate.localeCompare(right.localDate));
}

function dailyReturns(points: PortfolioHistoryPoint[]): DailyReturn[] {
  const rows: DailyReturn[] = [];
  for (let index = 1; index < points.length; index += 1) {
    const previous = points[index - 1];
    const current = points[index];
    if (previous.totalUsd > 0) {
      rows.push({
        date: current.localDate,
        valueUsd: current.totalUsd,
        returnDecimal: (current.totalUsd - previous.totalUsd) / previous.totalUsd,
      });
    }
  }
  return rows;
}

function benchmarkDailyReturns(points: BenchmarkData["bitcoin"]): DailyReturn[] {
  const rows = points
    .map((point) => ({
      date: timestampDate(point.timestamp),
      valueUsd: point.index,
    }))
    .filter((point): point is { date: string; valueUsd: number } => point.date !== null && point.valueUsd > 0)
    .sort((left, right) => left.date.localeCompare(right.date));

  const returns: DailyReturn[] = [];
  for (let index = 1; index < rows.length; index += 1) {
    const previous = rows[index - 1];
    const current = rows[index];
    if (previous.valueUsd > 0) {
      returns.push({
        date: current.date,
        valueUsd: current.valueUsd,
        returnDecimal: (current.valueUsd - previous.valueUsd) / previous.valueUsd,
      });
    }
  }
  return returns;
}

function timestampDate(timestamp: number): string | null {
  const millis = timestamp > 1_000_000_000_000 ? timestamp : timestamp * 1000;
  const date = new Date(millis);
  return Number.isNaN(date.getTime()) ? null : date.toISOString().slice(0, 10);
}

function pickPrimaryBenchmark(stats: { bitcoin: BenchmarkStats; solana: BenchmarkStats }): BenchmarkStats {
  const candidates = [stats.bitcoin, stats.solana].filter((stat) => stat.beta != null || stat.correlation != null);
  if (candidates.length === 0) {
    return stats.bitcoin;
  }
  return candidates.sort((left, right) => Math.abs(right.correlation ?? 0) - Math.abs(left.correlation ?? 0))[0];
}

function largestScenarioLoss(metrics: PortfolioMetricsResponse | null, protocolExposureUsd: number): string {
  const values = [
    metrics ? metrics.concentrationUsd * 0.15 : 0,
    protocolExposureUsd * 0.2,
    metrics ? metrics.stableUsd * 0.02 : 0,
  ];
  return formatCurrency(Math.max(...values));
}

function benchmarkValue(stats: { bitcoin: BenchmarkStats; solana: BenchmarkStats }): string {
  const primary = pickPrimaryBenchmark(stats);
  return primary.beta == null ? "ACCUMULATING" : `${primary.label} ${primary.beta.toFixed(2)}`;
}

function benchmarkDetail(stats: { bitcoin: BenchmarkStats; solana: BenchmarkStats }): string {
  const primary = pickPrimaryBenchmark(stats);
  return primary.correlation == null ? "Benchmark sample building" : `${primary.correlation.toFixed(2)} correlation`;
}

function inputDetail(partialInputs: boolean, syncing: boolean): string {
  if (partialInputs) return "Some modules degraded";
  if (syncing) return "Live calculations running";
  return "All modules hydrated";
}

function metricNumber(value: number | null | undefined): string {
  return value == null ? "ACCUMULATING" : value.toFixed(2);
}

function formatCurrency(value: number): string {
  return new Intl.NumberFormat("en-US", {
    style: "currency",
    currency: "USD",
    maximumFractionDigits: Math.abs(value) >= 100 ? 0 : 2,
  }).format(value);
}

function formatPct(value: number): string {
  return `${value.toFixed(1)}%`;
}

function formatReturnPct(value: number): string {
  return `${(value * 100).toFixed(2)}%`;
}

function formatShortDateTime(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return date.toLocaleString("en-US", {
    month: "short",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
}

function formatNullablePct(value: number | null | undefined): string {
  return value == null ? "N/A" : `${value.toFixed(2)}%`;
}

function pct(value: number, total: number): number {
  return total <= 0 ? 0 : (value / total) * 100;
}

function lossUsd(returnDecimal: number, totalUsd: number): number {
  return Math.max(0, -returnDecimal * totalUsd);
}

function mean(values: number[]): number {
  return values.length === 0 ? 0 : values.reduce((sum, value) => sum + value, 0) / values.length;
}

function variance(values: number[]): number {
  if (values.length === 0) return 0;
  const avg = mean(values);
  return values.reduce((sum, value) => sum + Math.pow(value - avg, 2), 0) / values.length;
}

function stddev(values: number[]): number {
  return Math.sqrt(variance(values));
}

function covariance(left: number[], right: number[]): number {
  const size = Math.min(left.length, right.length);
  if (size === 0) return 0;
  const leftMean = mean(left.slice(0, size));
  const rightMean = mean(right.slice(0, size));
  let sum = 0;
  for (let index = 0; index < size; index += 1) {
    sum += (left[index] - leftMean) * (right[index] - rightMean);
  }
  return sum / size;
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(Math.max(value, min), max);
}

function drawdownRange(metrics: PortfolioMetricsResponse | null): string {
  if (metrics?.maxDrawdownPeakUsd == null || metrics.maxDrawdownTroughUsd == null) {
    return "Waiting for enough history";
  }
  return `${formatCurrency(metrics.maxDrawdownPeakUsd)} on ${metrics.maxDrawdownPeakDate ?? "unknown date"} to ${formatCurrency(metrics.maxDrawdownTroughUsd)} on ${metrics.maxDrawdownTroughDate ?? "unknown date"}`;
}

function readableProtocol(value: string): string {
  const trimmed = value.trim();
  if (!trimmed) {
    return "Unknown protocol";
  }
  return trimmed
    .split(/[-_\s]+/)
    .filter(Boolean)
    .map((part) => part.slice(0, 1).toUpperCase() + part.slice(1))
    .join(" ");
}

function concentrationMeaning(risk: PortfolioMetricsResponse["concentrationRisk"]): string {
  if (risk === "LOW") return "No single asset is carrying the portfolio.";
  if (risk === "MEDIUM") return "One asset matters enough to watch closely.";
  if (risk === "HIGH") return "One asset has strong control over portfolio movement.";
  return "One asset dominates the portfolio.";
}

function stableMeaning(value: number): string {
  if (value < 20) return "Mostly market-exposed, with a smaller cash buffer.";
  if (value <= 40) return "Balanced cash-like buffer.";
  return "Large cash-like allocation; lower market beta, more idle-capital risk.";
}

function riskTone(risk: PortfolioMetricsResponse["concentrationRisk"] | undefined): Tone {
  if (risk === "LOW") return "green";
  if (risk === "MEDIUM") return "amber";
  if (risk === "HIGH" || risk === "CRITICAL") return "red";
  return "muted";
}

function stableTone(value: number): Tone {
  if (value < 20) return "amber";
  if (value <= 40) return "green";
  if (value > 60) return "amber";
  return "blue";
}

function protocolTone(value: number): Tone {
  if (value > 45) return "red";
  if (value > 20) return "amber";
  if (value > 0) return "blue";
  return "green";
}

function ratioTone(value: number | null | undefined): Tone {
  if (value == null) return "muted";
  if (value < 0) return "red";
  if (value < 0.5) return "amber";
  if (value >= 1) return "green";
  return "blue";
}

function drawdownTone(value: number | null | undefined): Tone {
  if (value == null) return "muted";
  if (value <= -20) return "red";
  if (value <= -10) return "amber";
  return "green";
}

function tailRiskTone(value: number | null | undefined): Tone {
  if (value == null) return "muted";
  if (value <= -0.08) return "red";
  if (value <= -0.035) return "amber";
  if (value < 0) return "blue";
  return "green";
}

function benchmarkTone(stats: { bitcoin: BenchmarkStats; solana: BenchmarkStats }): Tone {
  const primary = pickPrimaryBenchmark(stats);
  if (primary.beta == null) return "muted";
  if (Math.abs(primary.beta) > 1.4) return "red";
  if (Math.abs(primary.beta) > 0.85) return "amber";
  return "blue";
}

function correlationTone(value: number | null): Tone {
  if (value == null) return "muted";
  if (Math.abs(value) > 0.8) return "red";
  if (Math.abs(value) > 0.55) return "amber";
  return "blue";
}

function scenarioTone(lossUsdValue: number, totalUsd: number): Tone {
  const share = pct(lossUsdValue, totalUsd);
  if (share > 12) return "red";
  if (share > 6) return "amber";
  if (share > 0) return "blue";
  return "muted";
}
