"use client";

import { useEffect, useMemo, useState } from "react";

import { fetchPortfolioMetrics, walletSetHash } from "@/lib/portfolio";
import type { PortfolioMetricsResponse, WalletRecord } from "@/types/portfolio";

type Props = {
  wallets: WalletRecord[];
  chains: string[];
};

type BadgeTone = "green" | "amber" | "red" | "muted";

export function PortfolioMetricsBlock({ wallets, chains }: Props) {
  const [metrics, setMetrics] = useState<PortfolioMetricsResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const walletHash = useMemo(() => walletSetHash(wallets), [wallets]);
  const chainKey = useMemo(() => chains.slice().sort().join("|"), [chains]);

  useEffect(() => {
    if (wallets.length === 0 || chains.length === 0) {
      setMetrics(null);
      setError(null);
      return;
    }

    let active = true;
    setLoading(true);
    setError(null);
    fetchPortfolioMetrics(wallets, chains)
      .then((response) => {
        if (active) {
          setMetrics(response);
        }
      })
      .catch((fetchError) => {
        if (active) {
          setMetrics(null);
          setError(fetchError instanceof Error ? fetchError.message : "METRICS_UNAVAILABLE");
        }
      })
      .finally(() => {
        if (active) {
          setLoading(false);
        }
      });

    return () => {
      active = false;
    };
  }, [walletHash, chainKey, wallets, chains]);

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
        />
        <MetricCard
          label="STABLES"
          value={metrics ? formatPct(metrics.stableAllocationPct) : loading ? "SYNCING..." : "N/A"}
          badge={metrics ? stableBadge(metrics.stableAllocationPct) : undefined}
          tone={metrics ? stableTone(metrics.stableAllocationPct) : "muted"}
        />
        <MetricCard
          label="DEFI"
          value={metrics ? formatPct(metrics.defiAllocationPct) : loading ? "SYNCING..." : "N/A"}
          badge={metrics ? defiBadge(metrics.defiAllocationPct) : undefined}
          tone={metrics ? defiTone(metrics.defiAllocationPct) : "muted"}
        />
        <MetricCard
          label="SHARPE 30D"
          value={metricNumber(metrics?.sharpe30d)}
          muted={metrics?.sharpe30d == null}
        />
        <MetricCard
          label="SORTINO 30D"
          value={metricNumber(metrics?.sortino30d)}
          muted={metrics?.sortino30d == null}
        />
        <MetricCard
          label="MAX DD 30D"
          value={metrics?.maxDrawdownPct30d == null ? "ACCUMULATING..." : formatPct(metrics.maxDrawdownPct30d)}
          muted={metrics?.maxDrawdownPct30d == null}
          tone={drawdownTone(metrics?.maxDrawdownPct30d)}
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
}: {
  label: string;
  value: string;
  badge?: string;
  tone?: BadgeTone;
  muted?: boolean;
}) {
  return (
    <article className={`s-metric-card ${muted ? "is-muted" : ""}`}>
      <span className="s-metric-label">{label}</span>
      <strong className="s-metric-value mono">{value}</strong>
      {badge ? <span className={`s-metric-badge is-${tone}`}>{badge}</span> : <span className="s-metric-badge is-muted">30D</span>}
    </article>
  );
}

function metricNumber(value: number | null | undefined): string {
  return value == null ? "ACCUMULATING..." : value.toFixed(2);
}

function formatPct(value: number): string {
  return `${value.toFixed(1)}%`;
}

function riskTone(risk: PortfolioMetricsResponse["concentrationRisk"] | undefined): BadgeTone {
  if (risk === "LOW") return "green";
  if (risk === "MEDIUM") return "amber";
  if (risk === "HIGH" || risk === "CRITICAL") return "red";
  return "muted";
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
