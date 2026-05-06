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
  PortfolioMetricsResponse,
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

export async function fetchPortfolioMetrics(
  wallets: WalletRecord[],
  chains: string[],
): Promise<PortfolioMetricsResponse> {
  const response = await fetch(`${API_BASE_URL}/v1/portfolio/metrics`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      addresses: wallets.map((wallet) => wallet.normalizedAddress),
      chains,
    }),
  });

  if (!response.ok) {
    const body = await safeError(response);
    throw new Error(body ?? "Failed to load portfolio metrics");
  }

  return (await response.json()) as PortfolioMetricsResponse;
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
    const canonical = canonicalAsset(asset);
    const key = canonical.symbol;
    const existing = byToken.get(key);
    if (existing) {
      existing.valueUsd += asset.valueUsd;
    } else {
      byToken.set(key, {
        network: key,
        displayName: canonical.symbol,
        valueUsd: asset.valueUsd,
      });
    }
  }
  return [...byToken.values()].sort((left, right) => right.valueUsd - left.valueUsd);
}

export function canonicalAsset(asset: AssetRow): {
  key: string;
  symbol: string;
  name: string;
  wrapped: boolean;
} {
  const symbol = normalizeAssetSymbol(asset.symbol);
  if (asset.nativeToken) {
    const native = nativeAssetMetadata(asset.network, symbol, asset.name);
    return {
      key: `canonical:${native.symbol}`,
      symbol: native.symbol,
      name: native.name,
      wrapped: false,
    };
  }

  if (symbol === "WETH") {
    return {
      key: "canonical:ETH",
      symbol: "ETH",
      name: "Ethereum",
      wrapped: true,
    };
  }

  if (symbol === "USDC" || symbol === "USDC.E") {
    return {
      key: "canonical:USDC",
      symbol: "USDC",
      name: "USD Coin",
      wrapped: false,
    };
  }

  if (symbol === "USDT") {
    return {
      key: "canonical:USDT",
      symbol: "USDT",
      name: "Tether",
      wrapped: false,
    };
  }

  const tokenKey = asset.tokenAddress ? `${asset.network}:${asset.tokenAddress.toLowerCase()}` : asset.assetId;
  return {
    key: tokenKey,
    symbol: symbol || "UNKNOWN",
    name: normalizeNativeName(asset.name) || "Unknown",
    wrapped: false,
  };
}

function nativeAssetMetadata(network: string, symbol: string, name: string): {
  symbol: string;
  name: string;
} {
  const normalizedNetwork = network.trim().toLowerCase();
  const normalizedName = normalizeNativeName(name);
  const known: Record<string, { symbol: string; name: string }> = {
    "eth-mainnet": { symbol: "ETH", name: "Ethereum" },
    "arb-mainnet": { symbol: "ETH", name: "Ethereum" },
    "arbnova-mainnet": { symbol: "ETH", name: "Ethereum" },
    "opt-mainnet": { symbol: "ETH", name: "Ethereum" },
    "base-mainnet": { symbol: "ETH", name: "Ethereum" },
    "zksync-mainnet": { symbol: "ETH", name: "Ethereum" },
    "linea-mainnet": { symbol: "ETH", name: "Ethereum" },
    "scroll-mainnet": { symbol: "ETH", name: "Ethereum" },
    "zora-mainnet": { symbol: "ETH", name: "Ethereum" },
    "blast-mainnet": { symbol: "ETH", name: "Ethereum" },
    "polygonzkevm-mainnet": { symbol: "ETH", name: "Ethereum" },
    "polygon-mainnet": { symbol: "MATIC", name: "Polygon" },
    "matic-mainnet": { symbol: "MATIC", name: "Polygon" },
    "bnb-mainnet": { symbol: "BNB", name: "BNB" },
    "avax-mainnet": { symbol: "AVAX", name: "Avalanche" },
    "celo-mainnet": { symbol: "CELO", name: "Celo" },
    "mantle-mainnet": { symbol: "MNT", name: "Mantle" },
    "moonbeam-mainnet": { symbol: "GLMR", name: "Moonbeam" },
    "berachain-mainnet": { symbol: "BERA", name: "Berachain" },
    "zetachain-mainnet": { symbol: "ZETA", name: "ZetaChain" },
    "apechain-mainnet": { symbol: "APE", name: "ApeCoin" },
    "solana-mainnet": { symbol: "SOL", name: "Solana" },
    solana: { symbol: "SOL", name: "Solana" },
  };
  return known[normalizedNetwork] ?? {
    symbol: symbol && symbol !== "NATIVE" ? symbol : "NATIVE",
    name: normalizedName || "Native Token",
  };
}

function normalizeAssetSymbol(symbol: string): string {
  return symbol.trim().toUpperCase();
}

function normalizeNativeName(name: string): string {
  return name.trim().toLowerCase() === "native token" ? "" : name.trim();
}
