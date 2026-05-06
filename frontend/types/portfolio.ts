export type WalletRecord = {
  normalizedAddress: string;
  originalInput: string;
  label: string;
  createdAt: string;
};

export type ChainAllocation = {
  network: string;
  displayName: string;
  valueUsd: number;
};

export type WalletAllocation = {
  walletAddress: string;
  valueUsd: number;
};

export type PortfolioSummaryResponse = {
  totalUsd: number;
  grossAssetUsd: number;
  grossLiabilityUsd: number;
  netUsd: number;
  trackedAssets: number;
  hiddenAssets: number;
  allocations: ChainAllocation[];
  walletAllocations: WalletAllocation[];
};

export type AssetRow = {
  assetId: string;
  network: string;
  tokenAddress: string | null;
  symbol: string;
  name: string;
  decimals: number;
  quantity: number;
  priceUsd: number;
  valueUsd: number;
  nativeToken: boolean;
  logoUrl: string | null;
};

export type LendingPositionResponse = {
  positionId: string;
  walletAddress: string;
  network: string;
  protocolKey: string;
  protocolName: string;
  positionSide: "supply" | "debt" | "net";
  detectionMode: "heuristic" | "adapter" | "protocol";
  coverage: "partial" | "complete" | "full";
  sourceAssetId: string;
  underlyingTokenAddress: string;
  underlyingSymbol: string;
  underlyingName: string;
  quantity: number;
  priceUsd: number;
  supplyUsd: number | null;
  debtUsd: number | null;
  netUsd: number | null;
  alreadyCountedInPortfolio: boolean;
};

export type LendingPositionSummaryResponse = {
  trackedPositions: number;
  partialCoverage: boolean;
  detectionModes: string[];
  visibleSupplyUsd: number;
  visibleDebtUsd: number;
  visibleNetUsd: number;
};

export type DefiPositionResponse = LendingPositionResponse & {
  positionType: string;
};

export type DefiPositionSummaryResponse = LendingPositionSummaryResponse;

export type PortfolioAssetsResponse = {
  assets: AssetRow[];
};

export type PortfolioOverviewResponse = {
  summary: PortfolioSummaryResponse;
  assets: AssetRow[];
  positions: LendingPositionResponse[];
  positionSummary: LendingPositionSummaryResponse;
  defiPositions: DefiPositionResponse[];
  defiSummary: DefiPositionSummaryResponse;
};

export type PortfolioHistoryPoint = {
  localDate: string;
  totalUsd: number;
  source: string;
  persisted: boolean;
};

export type PortfolioHistoryResponse = {
  period: "24h" | "7d" | "30d";
  scopeHash: string;
  points: PortfolioHistoryPoint[];
  partial: boolean;
  missingChains: string[];
  asOf: string;
};

export type PortfolioMetricsResponse = {
  concentrationPct: number;
  concentrationAsset: string;
  concentrationRisk: "LOW" | "MEDIUM" | "HIGH" | "CRITICAL";
  stableAllocationPct: number;
  defiAllocationPct: number;
  idleStableUsd: number;
  monthlyOpportunityCostUsd: number;
  sharpe30d: number | null;
  sortino30d: number | null;
  maxDrawdownPct30d: number | null;
  historyDaysAvailable: number;
};

export type BenchmarkPoint = {
  timestamp: number;
  index: number;
};

export type BenchmarkData = {
  bitcoin: BenchmarkPoint[];
  solana: BenchmarkPoint[];
};

export type ChainOption = {
  id: string;
  providerNetwork: string;
  displayName: string;
  family: string;
  enabled: boolean;
};

export type RefreshResult = {
  summary: PortfolioSummaryResponse;
  assets: AssetRow[];
  positions: LendingPositionResponse[];
  positionSummary: LendingPositionSummaryResponse;
  defiPositions: DefiPositionResponse[];
  defiSummary: DefiPositionSummaryResponse;
};
