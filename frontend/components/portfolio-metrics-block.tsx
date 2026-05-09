"use client";

import { useEffect, useMemo, useState } from "react";
import type { ReactNode } from "react";

import { fetchPortfolioMetrics, walletSetHash } from "@/lib/portfolio";
import type { PortfolioMetricsResponse, WalletRecord } from "@/types/portfolio";

type Props = {
  wallets: WalletRecord[];
  chains: string[];
  portfolioTotalUsd?: number;
  defiExposureUsd?: number;
  defiPositionCount?: number;
  defiLoading?: boolean;
  providedMetrics?: PortfolioMetricsResponse | null;
  providedLoading?: boolean;
  providedPartial?: boolean;
};

type BadgeTone = "green" | "amber" | "red" | "muted";

export function PortfolioMetricsBlock({
  wallets,
  chains,
  portfolioTotalUsd = 0,
  defiExposureUsd = 0,
  defiPositionCount = 0,
  defiLoading = false,
  providedMetrics,
  providedLoading,
  providedPartial,
}: Props) {
  const [localMetrics, setLocalMetrics] = useState<PortfolioMetricsResponse | null>(null);
  const [localLoading, setLocalLoading] = useState(false);
  const [localError, setLocalError] = useState<string | null>(null);
  const walletHash = useMemo(() => walletSetHash(wallets), [wallets]);
  const chainKey = useMemo(() => chains.slice().sort().join("|"), [chains]);
  const usesProvidedMetrics = providedMetrics !== undefined || providedLoading !== undefined || providedPartial !== undefined;
  const metrics = usesProvidedMetrics ? providedMetrics ?? null : localMetrics;
  const loading = usesProvidedMetrics ? Boolean(providedLoading) : localLoading;
  const error = usesProvidedMetrics ? (providedPartial ? "METRICS_PARTIAL" : null) : localError;
  const totalUsd = portfolioTotalUsd > 0 ? portfolioTotalUsd : metrics?.totalUsd ?? 0;
  const backendDefiExposureUsd = metrics?.defiExposureUsd ?? 0;
  const displayDefiExposureUsd = Math.max(backendDefiExposureUsd, defiExposureUsd);
  const displayDefiAllocationPct = pct(displayDefiExposureUsd, totalUsd);
  const displayDefiPositionCount = Math.max(metrics?.defiPositionCount ?? 0, defiPositionCount);

  useEffect(() => {
    if (usesProvidedMetrics) {
      return;
    }

    if (wallets.length === 0 || chains.length === 0) {
      setLocalMetrics(null);
      setLocalError(null);
      return;
    }

    let active = true;
    setLocalLoading(true);
    setLocalError(null);
    fetchPortfolioMetrics(wallets, chains)
      .then((response) => {
        if (active) {
          setLocalMetrics(response);
        }
      })
      .catch((fetchError) => {
        if (active) {
          setLocalMetrics(null);
          setLocalError(fetchError instanceof Error ? fetchError.message : "METRICS_UNAVAILABLE");
        }
      })
      .finally(() => {
        if (active) {
          setLocalLoading(false);
        }
      });

    return () => {
      active = false;
    };
  }, [usesProvidedMetrics, walletHash, chainKey, wallets, chains]);

  if (wallets.length === 0) {
    return null;
  }

  return (
    <section className="s-panel s-metrics-panel">
      <div className="s-panel-hd">
        <span>PORTFOLIO RISK METRICS</span>
        <span className="s-badge">{loading ? "SYNCING" : error ? "PARTIAL" : `${metrics?.historyDaysAvailable ?? 0}D HISTORY`}</span>
      </div>
      <div className="s-metrics-grid">
        <MetricCard
          label="CONCENTRATION"
          value={metrics ? `${metrics.concentrationAsset} ${formatPct(metrics.concentrationPct)}` : loading ? "SYNCING..." : "N/A"}
          badge={metrics?.concentrationRisk ?? (error ? "OFFLINE" : undefined)}
          tone={riskTone(metrics?.concentrationRisk)}
          tooltip={metrics ? (
            <MetricTooltip
              title="Concentration Risk"
              copy="This answers one simple question: how much of the portfolio depends on one asset. If the largest asset falls hard, this number shows how much of the portfolio is directly exposed."
              rows={[
                ["Largest asset", metrics.concentrationAsset],
                ["Largest value", formatCurrency(metrics.concentrationUsd)],
                ["Portfolio value", formatCurrency(totalUsd)],
                ["Label means", concentrationMeaning(metrics.concentrationRisk)],
              ]}
            />
          ) : null}
        />
        <MetricCard
          label="STABLES"
          value={metrics ? formatPct(metrics.stableAllocationPct) : loading ? "SYNCING..." : "N/A"}
          badge={metrics ? stableBadge(metrics.stableAllocationPct) : undefined}
          tone={metrics ? stableTone(metrics.stableAllocationPct) : "muted"}
          tooltip={metrics ? (
            <MetricTooltip
              title="Stablecoin Allocation"
              copy="This is the cash-like part of the portfolio. More stables usually means less market movement, but also more idle capital if the stables are not being used."
              rows={[
                ["Stable value", formatCurrency(metrics.stableUsd)],
                ["Idle stable value", formatCurrency(metrics.idleStableUsd)],
                ["Monthly cash drag", formatCurrency(metrics.monthlyOpportunityCostUsd)],
                ["Label means", stableMeaning(metrics.stableAllocationPct)],
              ]}
            />
          ) : null}
        />
        <MetricCard
          label="DEFI"
          value={metrics ? formatPct(displayDefiAllocationPct) : loading || defiLoading ? "SYNCING..." : "N/A"}
          badge={metrics ? defiBadge(displayDefiAllocationPct) : undefined}
          tone={metrics ? defiTone(displayDefiAllocationPct) : "muted"}
          tooltip={metrics ? (
            <MetricTooltip
              title="DeFi Exposure"
              copy="This is the part of the portfolio sitting inside protocols, vaults, pools, staking, lending, or rewards. It is shown even when the same token is already counted in the asset list."
              rows={[
                ["Protocol value", formatCurrency(displayDefiExposureUsd)],
                ["Portfolio share", formatPct(displayDefiAllocationPct)],
                ["Net DeFi", formatCurrency(metrics.defiNetUsd)],
                ["Positions", `${displayDefiPositionCount}`],
              ]}
            />
          ) : null}
        />
        <MetricCard
          label="SHARPE 30D"
          value={metricNumber(metrics?.sharpe30d)}
          muted={metrics?.sharpe30d == null}
          tooltip={metrics ? (
            <MetricTooltip
              title="Sharpe Ratio"
              copy="Reward-to-risk ratio over 30 days. Measures how much return you earned per unit of daily volatility. Above 1.0 is strong. Crypto portfolios typically score 0.5-1.5 in trending markets."
              rows={[
                ["Your score", metricNumber(metrics.sharpe30d)],
                ["Avg daily return", formatNullablePct(metrics.averageDailyReturnPct30d)],
                ["Daily volatility", formatNullablePct(metrics.dailyVolatilityPct30d)],
                ["Tiny example", "Same gain with smaller daily swings gets a higher Sharpe."],
              ]}
            />
          ) : null}
        />
        <MetricCard
          label="SORTINO 30D"
          value={metricNumber(metrics?.sortino30d)}
          muted={metrics?.sortino30d == null}
          tooltip={metrics ? (
            <MetricTooltip
              title="Sortino Ratio"
              copy="Like Sharpe, but only penalises downside volatility. Up days don't count against you - only losing days do. A higher Sortino than Sharpe means your losses were smaller and smoother than your gains."
              rows={[
                ["Your score", metricNumber(metrics.sortino30d)],
                ["Avg daily return", formatNullablePct(metrics.averageDailyReturnPct30d)],
                ["Downside deviation", formatNullablePct(metrics.downsideDeviationPct30d)],
                ["Tiny example", "Two portfolios gain 5%; the one with fewer red days gets the better Sortino."],
              ]}
            />
          ) : null}
        />
        <MetricCard
          label="MAX DD 30D"
          value={metrics?.maxDrawdownPct30d == null ? "ACCUMULATING..." : formatPct(metrics.maxDrawdownPct30d)}
          muted={metrics?.maxDrawdownPct30d == null}
          tone={drawdownTone(metrics?.maxDrawdownPct30d)}
          tooltip={metrics ? (
            <MetricTooltip
              title="Max Drawdown"
              copy="The largest peak-to-trough decline over 30 days. If your portfolio peaked at $100k and dropped to $83.2k before recovering, that's -16.8%. Measures worst-case pain, not average risk."
              rows={[
                ["Peak", metrics.maxDrawdownPeakUsd == null ? "N/A" : `${formatCurrency(metrics.maxDrawdownPeakUsd)} on ${formatDate(metrics.maxDrawdownPeakDate)}`],
                ["Trough", metrics.maxDrawdownTroughUsd == null ? "N/A" : `${formatCurrency(metrics.maxDrawdownTroughUsd)} on ${formatDate(metrics.maxDrawdownTroughDate)}`],
                ["History", historyWindow(metrics)],
                ["Result", metrics.maxDrawdownPct30d == null ? "N/A" : formatPct(metrics.maxDrawdownPct30d)],
              ]}
            />
          ) : null}
        />
      </div>
    </section>
  );
}

function MetricCard({
  label,
  value,
  badge,
  tone = "muted",
  muted = false,
  tooltip,
}: {
  label: string;
  value: string;
  badge?: string;
  tone?: BadgeTone;
  muted?: boolean;
  tooltip?: ReactNode;
}) {
  return (
    <article className={`s-metric-card ${muted ? "is-muted" : ""}`} tabIndex={tooltip ? 0 : undefined}>
      <span className="s-metric-label">{label}</span>
      <strong className="s-metric-value mono">{value}</strong>
      {badge ? <span className={`s-metric-badge is-${tone}`}>{badge}</span> : <span className="s-metric-badge is-muted">30D</span>}
      {tooltip ? <div className="s-metric-popover">{tooltip}</div> : null}
    </article>
  );
}

function MetricTooltip({
  title,
  copy,
  rows,
}: {
  title: string;
  copy: string;
  rows: Array<[string, string]>;
}) {
  return (
    <>
      <strong>{title}</strong>
      <p>{copy}</p>
      <dl>
        {rows.map(([term, detail]) => (
          <div key={term}>
            <dt>{term}</dt>
            <dd>{detail}</dd>
          </div>
        ))}
      </dl>
    </>
  );
}

function metricNumber(value: number | null | undefined): string {
  return value == null ? "ACCUMULATING..." : value.toFixed(2);
}

function formatCurrency(value: number): string {
  return new Intl.NumberFormat("en-US", {
    style: "currency",
    currency: "USD",
    maximumFractionDigits: 0,
  }).format(value);
}

function formatPct(value: number): string {
  return `${value.toFixed(1)}%`;
}

function formatNullablePct(value: number | null | undefined): string {
  return value == null ? "N/A" : `${value.toFixed(2)}%`;
}

function formatDate(value: string | null | undefined): string {
  return value ?? "N/A";
}

function historyWindow(metrics: PortfolioMetricsResponse): string {
  if (!metrics.historyStartDate || !metrics.historyEndDate) {
    return `${metrics.historyDaysAvailable}D available`;
  }
  return `${metrics.historyStartDate} to ${metrics.historyEndDate}`;
}

function pct(value: number, total: number): number {
  return total <= 0 ? 0 : (value / total) * 100;
}

function riskTone(risk: PortfolioMetricsResponse["concentrationRisk"] | undefined): BadgeTone {
  if (risk === "LOW") return "green";
  if (risk === "MEDIUM") return "amber";
  if (risk === "HIGH" || risk === "CRITICAL") return "red";
  return "muted";
}

function concentrationMeaning(risk: PortfolioMetricsResponse["concentrationRisk"]): string {
  if (risk === "LOW") return "No single asset is carrying the portfolio.";
  if (risk === "MEDIUM") return "One asset matters enough to watch closely.";
  if (risk === "HIGH") return "One asset has strong control over portfolio movement.";
  return "One asset dominates the portfolio.";
}

function stableBadge(value: number): string {
  if (value < 20) return "LOW";
  if (value <= 40) return "OK";
  return "HIGH";
}

function stableTone(value: number): BadgeTone {
  if (value < 20) return "amber";
  if (value <= 40) return "green";
  return "amber";
}

function stableMeaning(value: number): string {
  if (value < 20) return "Mostly market-exposed, with a smaller cash buffer.";
  if (value <= 40) return "Balanced cash-like buffer.";
  return "Large cash-like allocation; lower market beta, more idle-capital risk.";
}

function defiBadge(value: number): string {
  if (value > 40) return "HIGH";
  if (value >= 10) return "OK";
  return "LOW";
}

function defiTone(value: number): BadgeTone {
  if (value > 40) return "red";
  if (value >= 10) return "green";
  return "amber";
}

function drawdownTone(value: number | null | undefined): BadgeTone {
  if (value == null) return "muted";
  if (value <= -20) return "red";
  if (value <= -10) return "amber";
  return "muted";
}
