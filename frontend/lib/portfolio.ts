import {
  AssetRow,
  ChainAllocation,
  ChainOption,
  DefiPositionResponse,
  DefiPositionSummaryResponse,
  LendingPositionResponse,
  LendingPositionSummaryResponse,
  PortfolioHistoryPoint,
  PortfolioHistoryResponse,
  PortfolioOverviewResponse,
  PortfolioSummaryResponse,
  RefreshResult,
  WalletRecord,
} from "@/types/portfolio";

const API_BASE_URL =
  process.env.NEXT_PUBLIC_API_BASE_URL?.replace(/\/$/, "") ?? "http://localhost:8080/api";

export const PERIODS = ["24h", "7d", "30d"] as const;
export type Period = (typeof PERIODS)[number];

export function normalizeAddress(value: string): string {
  const trimmed = value.trim();
  return isValidEvmAddress(trimmed) ? trimmed.toLowerCase() : trimmed;
}

export function isValidEvmAddress(value: string): boolean {
  return /^0x[a-fA-F0-9]{40}$/.test(value.trim());
}

export function isValidSolanaAddress(value: string): boolean {
  return /^[1-9A-HJ-NP-Za-km-z]{32,44}$/.test(value.trim());
}

export function isValidWalletAddress(value: string): boolean {
  return isValidEvmAddress(value) || isValidSolanaAddress(value);
}

export function walletSetHash(wallets: WalletRecord[]): string {
  return wallets.map((wallet) => wallet.normalizedAddress).sort().join("|");
}

export function todayLocalDate(): string {
  const now = new Date();
  const formatter = new Intl.DateTimeFormat("sv-SE", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  });
  return formatter.format(now);
}

export async function fetchChains(): Promise<ChainOption[]> {
  const response = await fetch(`${API_BASE_URL}/chains`, { cache: "no-store" });
  if (!response.ok) {
    throw new Error("Failed to load supported chains");
  }
  return (await response.json()) as ChainOption[];
}

export async function refreshPortfolio(
  wallets: WalletRecord[],
  chains: string[],
): Promise<RefreshResult> {
  const payload = {
    addresses: wallets.map((wallet) => wallet.normalizedAddress),
    chains,
  };

  const overviewResponse = await fetch(`${API_BASE_URL}/portfolio/overview`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });

  if (!overviewResponse.ok) {
    const body = await safeError(overviewResponse);
    throw new Error(body ?? "Failed to load portfolio overview");
  }

  const overview = (await overviewResponse.json()) as PortfolioOverviewResponse;

  return {
    summary: overview.summary,
    assets: overview.assets,
    positions: overview.positions ?? [],
    positionSummary: overview.positionSummary,
    defiPositions: overview.defiPositions ?? [],
    defiSummary: overview.defiSummary,
  };
}

export async function fetchPortfolioHistory(
  wallets: WalletRecord[],
  chains: string[],
  period: Period,
): Promise<PortfolioHistoryResponse> {
  const response = await fetch(`${API_BASE_URL}/portfolio/history`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      addresses: wallets.map((wallet) => wallet.normalizedAddress),
      chains,
      period,
    }),
  });

  if (!response.ok) {
    const body = await safeError(response);
    throw new Error(body ?? "Failed to load portfolio history");
  }

  return (await response.json()) as PortfolioHistoryResponse;
}

async function safeError(response: Response): Promise<string | null> {
  try {
    const data = (await response.json()) as { message?: string };
    return data.message ?? null;
  } catch {
    return null;
  }
}

export function computePeriodDelta(
  points: PortfolioHistoryPoint[],
  currentTotalUsd: number,
  period: Period,
): {
  amount: number | null;
  percentage: number | null;
  label: string;
} {
  if (points.length === 0) {
    return { amount: null, percentage: null, label: "Not enough history yet" };
  }

  const comparePoint = pickComparisonPoint(points, period);
  if (!comparePoint || comparePoint.totalUsd === 0) {
    return { amount: null, percentage: null, label: "Not enough history yet" };
  }

  const amount = currentTotalUsd - comparePoint.totalUsd;
  const percentage = (amount / comparePoint.totalUsd) * 100;

  return {
    amount,
    percentage,
    label: `${period} change`,
  };
}

function pickComparisonPoint(points: PortfolioHistoryPoint[], period: Period): PortfolioHistoryPoint | null {
  if (points.length === 0) {
    return null;
  }

  const days = period === "24h" ? 1 : period === "7d" ? 7 : 30;
  const targetDate = new Date();
  targetDate.setDate(targetDate.getDate() - days);
  const targetLabel = targetDate.toISOString().slice(0, 10);
  let candidate: PortfolioHistoryPoint | null = null;
  for (const point of points) {
    if (point.localDate <= targetLabel) {
      candidate = point;
    }
  }
  return candidate ?? points[0] ?? null;
}

export function groupAllocationsByToken(assets: AssetRow[]): ChainAllocation[] {
  const byToken = new Map<string, ChainAllocation>();
  for (const asset of assets) {
    const key = asset.symbol;
    const existing = byToken.get(key);
    if (existing) {
      existing.valueUsd += asset.valueUsd;
    } else {
      byToken.set(key, {
        network: key,
        displayName: asset.symbol,
        valueUsd: asset.valueUsd,
      });
    }
  }
  return [...byToken.values()].sort((left, right) => right.valueUsd - left.valueUsd);
}
