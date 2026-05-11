"use client";

import { FormEvent, useEffect, useMemo, useRef, useState } from "react";

import { DefiPositionsBlock } from "@/components/defi-positions-block";
import type { SolanaDefiTotals } from "@/components/defi-positions-block";
import { AiAdvisorBlock } from "@/components/ai-advisor-block";
import { PortfolioMetricsBlock } from "@/components/portfolio-metrics-block";
import { RiskEngineView } from "@/components/risk-engine-view";
import {
  canonicalAsset,
  computeHeroDelta,
  computePeriodDelta,
  fetchChains,
  fetchPortfolioBenchmarks,
  fetchPortfolioHistory,
  fetchPortfolioMetrics,
  groupAllocationsByToken,
  isValidEvmAddress,
  isValidSolanaAddress,
  isValidWalletAddress,
  normalizeAddress,
  PERIODS,
  Period,
  refreshPortfolio,
  walletSetHash,
} from "@/lib/portfolio";
import {
  clearSessionCache,
  readSessionCache,
  scopedSessionCacheKey,
  writeSessionCache,
} from "@/lib/session-cache";
import {
  getMeta,
  listWallets,
  readPortfolioSnapshotPair,
  recordPortfolioSnapshot,
  removeWallet,
  saveWallet,
  setMeta,
} from "@/lib/storage";
import type { PortfolioSnapshotPair } from "@/lib/storage";
import type {
  AssetRow,
  ChainAllocation,
  ChainOption,
  DefiPositionResponse,
  DefiPositionSummaryResponse,
  LendingPositionResponse,
  LendingPositionSummaryResponse,
  PortfolioHistoryPoint,
  PortfolioHistoryResponse,
  BenchmarkData,
  PortfolioMetricsResponse,
  PortfolioSummaryResponse,
  WalletRecord,
} from "@/types/portfolio";

const SELECTED_CHAINS_KEY = "selected-chains";
const SELECTED_CHAINS_CATALOG_KEY = "selected-chains-catalog";
const SWATCHES = ["#2f78d1", "#6d7fe7", "#5975db", "#f07a2c", "#bfdc3c", "#7ec8d8", "#12a7a1", "#a6a0dd"];
const OTHER_SWATCH = "#5d6674";
const MAX_ALLOCATION_SLICES = 6;
const PORTFOLIO_METRICS_CACHE_PREFIX = "portfolio-risk-metrics-v1";
const PORTFOLIO_METRICS_CACHE_TTL_MS = 10 * 60 * 1000;
const EMPTY_SOLANA_DEFI_TOTALS: SolanaDefiTotals = {
  totalValueUsd: 0,
  protocolExposureUsd: 0,
  protocolPositionCount: 0,
  protocolValues: {},
  protocolPositionCounts: {},
  walletValues: {},
  chainValues: {},
  tokenValues: {},
  reportedChange24hUsd: null,
  reportedChange24hPositionCount: 0,
  snapshottedValueUsd: 0,
  snapshottedPositionCount: 0,
  loading: false,
};

type AllocationMode = "token" | "chain" | "wallet";
type AllocationView = "strip" | "ring";
type MainView = "dashboard" | "risk-engine";

type ChartPoint = {
  label: string;
  localDate: string;
  value: number;
  current: boolean;
  timestampLabel: string;
  tooltipContext: string;
};

type AllocationSourceBreakdown = {
  label: string;
  valueUsd: number;
};

type AllocationInputRow = ChainAllocation & {
  sourceBreakdown?: AllocationSourceBreakdown[];
};

type AllocationChartRow = AllocationInputRow & {
  share: number;
  color: string;
  grouped: boolean;
  groupedCount: number;
};

type AssetInventoryRow = {
  key: string;
  symbol: string;
  name: string;
  quantity: number;
  priceUsd: number;
  valueUsd: number;
  networks: string[];
  networkBreakdown: Array<{
    network: string;
    label: string;
    valueUsd: number;
    share: number;
  }>;
  networkLabel: string;
  typeLabel: string;
};

export function Dashboard() {
  const [wallets, setWallets] = useState<WalletRecord[]>([]);
  const [chains, setChains] = useState<ChainOption[]>([]);
  const [selectedChains, setSelectedChains] = useState<string[]>([]);
  const [summary, setSummary] = useState<PortfolioSummaryResponse | null>(null);
  const [history, setHistory] = useState<PortfolioHistoryResponse | null>(null);
  const [benchmarks, setBenchmarks] = useState<BenchmarkData | null>(null);
  const [portfolioMetrics, setPortfolioMetrics] = useState<PortfolioMetricsResponse | null>(null);
  const [metricsLoading, setMetricsLoading] = useState(false);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [benchmarkLoading, setBenchmarkLoading] = useState(false);
  const [metricsPartial, setMetricsPartial] = useState(false);
  const [historyPartial, setHistoryPartial] = useState(false);
  const [benchmarkPartial, setBenchmarkPartial] = useState(false);
  const [assets, setAssets] = useState<AssetRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [inputAddress, setInputAddress] = useState("");
  const [inputLabel, setInputLabel] = useState("");
  const [duplicateAddress, setDuplicateAddress] = useState<string | null>(null);
  const [activeWallet, setActiveWallet] = useState<string | null>(null);
  const [period, setPeriod] = useState<Period>("30d");
  const [allocationMode, setAllocationMode] = useState<AllocationMode>("token");
  const [overviewAllocationView, setOverviewAllocationView] = useState<AllocationView>("strip");
  const [activeView, setActiveView] = useState<MainView>("dashboard");
  const [hoveredAllocationKey, setHoveredAllocationKey] = useState<string | null>(null);
  const [lastRefresh, setLastRefresh] = useState<string | null>(null);
  const [positions, setPositions] = useState<LendingPositionResponse[]>([]);
  const [defiPositions, setDefiPositions] = useState<DefiPositionResponse[]>([]);
  const [positionSummary, setPositionSummary] = useState<LendingPositionSummaryResponse | null>(null);
  const [defiSummary, setDefiSummary] = useState<DefiPositionSummaryResponse | null>(null);
  const [solanaDefiTotals, setSolanaDefiTotals] = useState<SolanaDefiTotals>(EMPTY_SOLANA_DEFI_TOTALS);
  const [visibleBenchmarks, setVisibleBenchmarks] = useState({ bitcoin: true, solana: true });
  const [portfolioSnapshotPair, setPortfolioSnapshotPair] = useState<PortfolioSnapshotPair>({ today: null, prev: null });

  useEffect(() => {
    const bootstrap = async () => {
      try {
        const [storedWallets, supportedChains, savedSelectedChains, savedChainCatalog, savedLastRefresh] =
          await Promise.all([
            listWallets(),
            fetchChains(),
            getMeta(SELECTED_CHAINS_KEY),
            getMeta(SELECTED_CHAINS_CATALOG_KEY),
            getMeta("last-refresh"),
          ]);
        const chainSelection = reconcileSelectedChains(supportedChains, savedSelectedChains, savedChainCatalog);
        const chainCatalogSignature = catalogSignature(supportedChains);

        setWallets(storedWallets);
        setChains(supportedChains);
        setSelectedChains(chainSelection);
        setLastRefresh(savedLastRefresh);
        await Promise.all([
          setMeta(SELECTED_CHAINS_CATALOG_KEY, chainCatalogSignature),
          chainSelection.join(",") !== (savedSelectedChains ?? "") ? setMeta(SELECTED_CHAINS_KEY, chainSelection.join(",")) : Promise.resolve(),
        ]);
      } catch (bootstrapError) {
        setError(bootstrapError instanceof Error ? bootstrapError.message : "Failed to load local data");
      } finally {
        setLoading(false);
      }
    };

    void bootstrap();
  }, []);

  useEffect(() => {
    if (!loading && wallets.length > 0 && selectedChains.length > 0) {
      void handleRefresh(false);
    }
  }, [loading, wallets.length, selectedChains.join("|")]);

  const walletHash = useMemo(() => walletSetHash(wallets), [wallets]);
  const selectedChainKey = useMemo(() => selectedChains.slice().sort().join("|"), [selectedChains]);
  const portfolioMetricsCacheKey = useMemo(
    () => scopedSessionCacheKey(PORTFOLIO_METRICS_CACHE_PREFIX, [walletHash, selectedChainKey]),
    [selectedChainKey, walletHash],
  );
  const baseTotalUsd = summary?.totalUsd ?? 0;
  const totalUsd = baseTotalUsd + solanaDefiTotals.totalValueUsd;
  const netWorthComputing = wallets.length > 0 && (refreshing || solanaDefiTotals.loading);
  const syncValueLoading = wallets.length > 0 && totalUsd === 0 && netWorthComputing;
  const chartDelta = useMemo(
    () => computePeriodDelta(history?.points ?? [], period),
    [history?.points, period],
  );
  const heroHistoryDelta = useMemo(
    () => computePeriodDelta(history?.points ?? [], "24h"),
    [history?.points],
  );
  const solanaSpotUsd = useMemo(
    () => sumAllocationForNetwork(summary?.allocations ?? [], "solana"),
    [summary?.allocations],
  );
  const heroDelta = useMemo(
    () =>
      computeHeroDelta(totalUsd, heroHistoryDelta, portfolioSnapshotPair.prev, {
        solanaSpotUsd,
        unreportedDefiUsd: solanaDefiTotals.snapshottedValueUsd,
        reportedDefiChange24hUsd: solanaDefiTotals.reportedChange24hUsd,
      }),
    [
      heroHistoryDelta,
      portfolioSnapshotPair.prev,
      solanaDefiTotals.reportedChange24hUsd,
      solanaDefiTotals.snapshottedValueUsd,
      solanaSpotUsd,
      totalUsd,
    ],
  );
  const chartPoints = useMemo(
    () => buildChartPoints(
      history?.points ?? [],
      null,
      period,
    ),
    [history?.points, period],
  );
  const walletAllocationRows = useMemo(
    () => buildWalletAllocationRows(summary?.walletAllocations ?? [], wallets, solanaDefiTotals.walletValues),
    [summary?.walletAllocations, wallets, solanaDefiTotals.walletValues],
  );
  const chainAllocationRows = useMemo(
    () => buildChainAllocationRows(summary?.allocations ?? [], solanaDefiTotals.chainValues),
    [summary?.allocations, solanaDefiTotals.chainValues],
  );
  const assetInventoryRows = useMemo(() => buildAssetInventoryRows(assets), [assets]);
  const backendDefiTokenValues = useMemo(
    () => buildBackendDefiTokenValues(defiPositions.length > 0 ? defiPositions : positions),
    [defiPositions, positions],
  );
  const defiTokenValues = useMemo(
    () => mergeValueMaps(backendDefiTokenValues, solanaDefiTotals.tokenValues),
    [backendDefiTokenValues, solanaDefiTotals.tokenValues],
  );
  const tokenAllocationRows = useMemo(
    () => buildTokenAllocationRows(assets, defiTokenValues),
    [assets, defiTokenValues],
  );
  const allocationRows = useMemo(
    () =>
      allocationMode === "token"
        ? tokenAllocationRows
        : allocationMode === "wallet"
          ? walletAllocationRows
          : chainAllocationRows,
    [allocationMode, chainAllocationRows, tokenAllocationRows, walletAllocationRows],
  );
  const allocationChartRows = useMemo(
    () => buildAllocationChartRows(allocationRows, totalUsd),
    [allocationRows, totalUsd],
  );
  const activeAllocationRow = useMemo(
    () =>
      allocationChartRows.find((row) => allocationRowKey(row) === hoveredAllocationKey) ?? allocationChartRows[0] ?? null,
    [allocationChartRows, hoveredAllocationKey],
  );
  const summaryAllocationChartRows = useMemo(
    () => buildAllocationChartRows(allocationRows, totalUsd),
    [allocationRows, totalUsd],
  );
  const summaryActiveAllocationRow = useMemo(
    () =>
      summaryAllocationChartRows.find((row) => allocationRowKey(row) === hoveredAllocationKey) ?? summaryAllocationChartRows[0] ?? null,
    [summaryAllocationChartRows, hoveredAllocationKey],
  );
  const allocationMeta = useMemo(() => allocationModeMeta(allocationMode), [allocationMode]);
  const activeDefiPositions = useMemo(
    () => (defiPositions.length > 0 ? defiPositions : positions),
    [defiPositions, positions],
  );
  const activeDefiSummary = useMemo(
    () => (defiPositions.length > 0 ? (defiSummary ?? positionSummary) : positionSummary),
    [defiPositions.length, defiSummary, positionSummary],
  );
  const defiProtocolGroups = useMemo(() => groupByProtocol(activeDefiPositions), [activeDefiPositions]);

  async function handleRefresh(manual = true) {
    if (wallets.length === 0) {
      setSummary(null);
      setHistory(null);
      setBenchmarks(null);
      setPortfolioMetrics(null);
      setMetricsLoading(false);
      setHistoryLoading(false);
      setBenchmarkLoading(false);
      setMetricsPartial(false);
      setHistoryPartial(false);
      setBenchmarkPartial(false);
      setAssets([]);
      setPositions([]);
      setDefiPositions([]);
      setPositionSummary(null);
      setDefiSummary(null);
      setSolanaDefiTotals(EMPTY_SOLANA_DEFI_TOTALS);
      setError(null);
      return;
    }
    setRefreshing(true);
    setError(null);
    try {
      const data = await refreshPortfolio(wallets, selectedChains);
      setSummary(data.summary);
      setAssets(data.assets);
      setPositions(data.positions);
      setDefiPositions(data.defiPositions);
      setPositionSummary(data.positionSummary ?? null);
      setDefiSummary(data.defiSummary ?? null);
      if (manual) {
        clearSessionCache(portfolioMetricsCacheKey);
        setPortfolioMetrics(null);
        setMetricsPartial(false);
      }

      const refreshTime = new Date().toISOString();
      setLastRefresh(refreshTime);
      await setMeta("last-refresh", refreshTime);
      if (manual) {
        await setMeta("last-manual-refresh", refreshTime);
      }
    } catch (refreshError) {
      setError(refreshError instanceof Error ? refreshError.message : "Refresh failed");
    } finally {
      setRefreshing(false);
    }
  }

  async function handleAddWallet(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const entries = parseWalletInput(inputAddress);

    if (entries.length === 0) {
      setError("Please enter at least one EVM or Solana address.");
      return;
    }

    const invalidEntries = entries.filter((entry) => !isValidWalletAddress(entry));
    if (invalidEntries.length > 0) {
      setError(`Invalid wallet address${invalidEntries.length > 1 ? "es" : ""}: ${invalidEntries.slice(0, 3).join(", ")}.`);
      return;
    }

    const existingAddresses = new Set(wallets.map((wallet) => wallet.normalizedAddress));
    const batchAddresses = new Set<string>();
    const nextWallets: WalletRecord[] = [];
    let duplicateCount = 0;

    for (const entry of entries) {
      const normalized = normalizeAddress(entry);
      if (existingAddresses.has(normalized) || batchAddresses.has(normalized)) {
        duplicateCount++;
        continue;
      }

      batchAddresses.add(normalized);
      nextWallets.push({
        normalizedAddress: normalized,
        originalInput: entry,
        label: inputLabel.trim(),
        createdAt: new Date().toISOString(),
      });
    }

    if (nextWallets.length === 0) {
      const firstDuplicate = entries.map(normalizeAddress).find((address) => existingAddresses.has(address)) ?? null;
      setDuplicateAddress(firstDuplicate);
      setActiveWallet(firstDuplicate);
      setError("Wallet already added. Opened the existing entry.");
      return;
    }

    await Promise.all(nextWallets.map((wallet) => saveWallet(wallet)));
    const addedSolanaWallet = nextWallets.some((wallet) => isValidSolanaAddress(wallet.normalizedAddress));
    if (addedSolanaWallet && chains.some((chain) => chain.id === "solana") && !selectedChains.includes("solana")) {
      const nextSelection = [...selectedChains, "solana"];
      setSelectedChains(nextSelection);
      await setMeta(SELECTED_CHAINS_KEY, nextSelection.join(","));
    }
    setHistory(null);
    setBenchmarks(null);
    setPortfolioMetrics(null);
    setMetricsPartial(false);
    setHistoryPartial(false);
    setBenchmarkPartial(false);
    setWallets((current) => [...current, ...nextWallets]);
    setInputAddress("");
    setInputLabel("");
    setDuplicateAddress(null);
    setActiveWallet(nextWallets[nextWallets.length - 1].normalizedAddress);
    setError(
      duplicateCount > 0
        ? `${nextWallets.length} wallet${nextWallets.length > 1 ? "s" : ""} added. ${duplicateCount} duplicate${duplicateCount > 1 ? "s" : ""} skipped.`
        : null,
    );
  }

  async function handleRemoveWallet(address: string) {
    await removeWallet(address);
    setWallets((current) => current.filter((wallet) => wallet.normalizedAddress !== address));
    setActiveWallet(null);
    setSummary(null);
    setHistory(null);
    setBenchmarks(null);
    setPortfolioMetrics(null);
    setMetricsPartial(false);
    setHistoryPartial(false);
    setBenchmarkPartial(false);
    setAssets([]);
    setPositions([]);
    setDefiPositions([]);
    setPositionSummary(null);
    setDefiSummary(null);
    setSolanaDefiTotals(EMPTY_SOLANA_DEFI_TOTALS);
  }

  async function toggleChain(chainId: string) {
    const nextSelection = selectedChains.includes(chainId)
      ? selectedChains.filter((item) => item !== chainId)
      : [...selectedChains, chainId];
    if (nextSelection.length === 0) {
      return;
    }
    setHistory(null);
    setBenchmarks(null);
    setPortfolioMetrics(null);
    setMetricsPartial(false);
    setHistoryPartial(false);
    setBenchmarkPartial(false);
    setSelectedChains(nextSelection);
    await setMeta(SELECTED_CHAINS_KEY, nextSelection.join(","));
  }

  useEffect(() => {
    if (!walletHash) {
      setPortfolioSnapshotPair({ today: null, prev: null });
      return;
    }
    let active = true;
    void readPortfolioSnapshotPair(walletHash).then((pair) => {
      if (active) {
        setPortfolioSnapshotPair(pair);
      }
    });
    return () => {
      active = false;
    };
  }, [walletHash]);

  useEffect(() => {
    if (
      !walletHash ||
      wallets.length === 0 ||
      refreshing ||
      solanaDefiTotals.loading ||
      summary === null ||
      totalUsd <= 0
    ) {
      return;
    }
    const today = new Date().toISOString().slice(0, 10);
    if (
      portfolioSnapshotPair.today?.date === today &&
      portfolioSnapshotPair.today.totalUsd === totalUsd &&
      portfolioSnapshotPair.today.solanaSpotUsd === solanaSpotUsd &&
      portfolioSnapshotPair.today.unreportedDefiUsd === solanaDefiTotals.snapshottedValueUsd
    ) {
      return;
    }
    let active = true;
    void recordPortfolioSnapshot(walletHash, today, {
      totalUsd,
      solanaSpotUsd,
      unreportedDefiUsd: solanaDefiTotals.snapshottedValueUsd,
    }).then((pair) => {
      if (active) {
        setPortfolioSnapshotPair(pair);
      }
    });
    return () => {
      active = false;
    };
  }, [
    walletHash,
    wallets.length,
    refreshing,
    solanaDefiTotals.loading,
    solanaDefiTotals.snapshottedValueUsd,
    solanaSpotUsd,
    totalUsd,
    summary,
    portfolioSnapshotPair,
  ]);

  useEffect(() => {
    if (loading || wallets.length === 0 || selectedChains.length === 0 || summary === null) {
      return;
    }

    let active = true;
    const cachedMetrics = readSessionCache<PortfolioMetricsResponse>(
      portfolioMetricsCacheKey,
      PORTFOLIO_METRICS_CACHE_TTL_MS,
    );
    if (cachedMetrics) {
      setPortfolioMetrics(cachedMetrics);
      setMetricsPartial(false);
      setMetricsLoading(false);
      return;
    }

    setMetricsLoading(true);
    setMetricsPartial(false);

    fetchPortfolioMetrics(wallets, selectedChains)
      .then((response) => {
        if (active) {
          setPortfolioMetrics(response);
          writeSessionCache(portfolioMetricsCacheKey, response);
        }
      })
      .catch(() => {
        if (active) {
          setPortfolioMetrics(null);
          setMetricsPartial(true);
        }
      })
      .finally(() => {
        if (active) {
          setMetricsLoading(false);
        }
      });

    return () => {
      active = false;
    };
  }, [loading, walletHash, selectedChainKey, summary, wallets, selectedChains, portfolioMetricsCacheKey]);

  useEffect(() => {
    if (loading || wallets.length === 0 || selectedChains.length === 0 || summary === null) {
      return;
    }

    let active = true;
    const loadHistory = async () => {
      setHistoryLoading(true);
      setBenchmarkLoading(false);
      setHistoryPartial(false);
      setBenchmarkPartial(false);
      try {
        const response = await fetchPortfolioHistory(wallets, selectedChains, "30d");
        if (!active) {
          return;
        }
        setHistory(response);
        const startTimestamp = historyStartTimestamp(response.points);
        if (startTimestamp === null) {
          setBenchmarks(null);
          setBenchmarkPartial(true);
          return;
        }
        try {
          setBenchmarkLoading(true);
          const benchmarkResponse = await fetchPortfolioBenchmarks(startTimestamp);
          if (active) {
            setBenchmarks(benchmarkResponse);
          }
        } catch {
          if (active) {
            setBenchmarks(null);
            setBenchmarkPartial(true);
          }
        } finally {
          if (active) {
            setBenchmarkLoading(false);
          }
        }
      } catch {
        if (active) {
          setHistory(null);
          setBenchmarks(null);
          setHistoryPartial(true);
          setBenchmarkPartial(true);
        }
      } finally {
        if (active) {
          setHistoryLoading(false);
        }
      }
    };

    void loadHistory();

    return () => {
      active = false;
    };
  }, [loading, walletHash, selectedChainKey, summary, wallets, selectedChains]);

  if (loading) {
    return (
      <div className="s-layout">
        <header className="s-topbar">
          <a className="s-brand" href="/" aria-label="P&L Terminal">
            <img className="s-brand-logo" src="/logo.png" alt="P&L Terminal" />
          </a>
        </header>
        <div className="s-loading">
          <p className="s-kicker">INITIALIZING TERMINAL</p>
          <p className="s-loading-msg">Loading local vault, scope matrix, and portfolio state...</p>
        </div>
        <footer className="s-footer">
          <div className="s-footer-left"><span className="s-dot" />BOOTING</div>
        </footer>
      </div>
    );
  }

  const topNetwork = chainAllocationRows[0]?.displayName ?? "—";
  const topWallet = walletAllocationRows[0]?.displayName ?? "—";
  const scopeLabel =
    chains.length === 0
      ? "Loading scope"
      : selectedChains.length === chains.length
        ? `All ${chains.length} networks`
        : `${selectedChains.length}/${chains.length} networks`;

  return (
    <div className="s-layout">

      {/* ── TOP NAVBAR ─────────────────────────────────────────────── */}
      <header className="s-topbar">
        <div className="s-topbar-left">
          <a className="s-brand" href="/" aria-label="P&L Terminal">
            <img className="s-brand-logo" src="/logo.png" alt="P&L Terminal" />
          </a>
          <nav className="s-topnav">
            <span className="s-topnav-active">{activeView === "risk-engine" ? "RISK ENGINE" : "LIVE SYNC"}</span>
            <span className="s-topnav-link">{scopeLabel.toUpperCase()}</span>
          </nav>
        </div>
        <div className="s-topbar-right">
          <span className="s-topbar-meta mono">{walletHash ? shortHash(walletHash) : "NO HASH"}</span>
          <button
            className="s-btn-primary"
            type="button"
            onClick={() => void handleRefresh(true)}
            disabled={refreshing || wallets.length === 0}
          >
            {refreshing ? "SYNCING..." : "SYNC LIVE"}
          </button>
          <span className="s-btn-outline s-btn-sm">AUTHORIZE PRIVATE</span>
        </div>
      </header>

      {/* ── LEFT SIDEBAR ───────────────────────────────────────────── */}
      <aside className="s-sidebar">

        {/* Nav */}
        <nav className="s-sidenav">
          <button
            className={`s-sidenav-item ${activeView === "dashboard" ? "s-sidenav-active" : ""}`}
            type="button"
            onClick={() => setActiveView("dashboard")}
          >
            DASHBOARD
          </button>
          <button className="s-sidenav-item" type="button" onClick={() => setActiveView("dashboard")}>ANALYTICS</button>
          <button className="s-sidenav-item" type="button" onClick={() => setActiveView("dashboard")}>ASSET INVENTORY</button>
          <button
            className={`s-sidenav-item ${activeView === "risk-engine" ? "s-sidenav-active" : ""}`}
            type="button"
            onClick={() => setActiveView("risk-engine")}
          >
            RISK ENGINE
          </button>
        </nav>

        {/* Wallet intake */}
        <div className="s-sidebar-section">
          <div className="s-sidebar-section-hd">WALLET INTAKE</div>
          <form className="s-form" onSubmit={handleAddWallet}>
            <label className="s-field">
              <span className="s-field-label">ADDRESSES</span>
              <textarea
                className="s-input s-wallet-address-input"
                value={inputAddress}
                onChange={(e) => setInputAddress(e.target.value)}
                placeholder="0x... / Solana address, one per line"
                rows={4}
              />
            </label>
            <label className="s-field">
              <span className="s-field-label">LABEL</span>
              <input
                className="s-input"
                value={inputLabel}
                onChange={(e) => setInputLabel(e.target.value)}
                placeholder="Main / trading / treasury"
              />
            </label>
            <button className="s-btn-outline" type="submit">REGISTER WALLETS</button>
          </form>
          {error ? <p className="s-error">{error}</p> : null}
        </div>

        {/* Tracked wallets */}
        {wallets.length > 0 && (
          <div className="s-sidebar-section">
            <div className="s-sidebar-section-hd">
              <span>TRACKED WALLETS</span>
              <span className="s-badge">{wallets.length}</span>
            </div>
            <div className="s-wallet-list">
              {wallets.map((wallet, i) => (
                <div
                  key={wallet.normalizedAddress}
                  className={`s-wallet-item ${activeWallet === wallet.normalizedAddress ? "is-active" : ""}`}
                >
                  <div className="s-wallet-swatch" style={{ background: SWATCHES[i % SWATCHES.length] }} />
                  <div className="s-wallet-info">
                    <span className="s-wallet-label">{wallet.label || "UNLABELED"}</span>
                    <span className="s-wallet-addr mono">{shortAddress(wallet.originalInput)}</span>
                  </div>
                  <button
                    className="s-wallet-remove"
                    type="button"
                    onClick={() => void handleRemoveWallet(wallet.normalizedAddress)}
                  >
                    ×
                  </button>
                </div>
              ))}
            </div>
          </div>
        )}

        {/* Network scope */}
        <div className="s-sidebar-section">
          <div className="s-sidebar-section-hd">
            <span>NETWORK SCOPE</span>
            <span className="s-badge">{selectedChains.length}</span>
          </div>
          <div className="s-chain-grid">
            {chains.map((chain) => (
              <button
                key={chain.id}
                className={`s-chain-btn ${selectedChains.includes(chain.id) ? "is-active" : ""}`}
                type="button"
                onClick={() => void toggleChain(chain.id)}
              >
                {chain.displayName}
              </button>
            ))}
          </div>
        </div>

        {/* Footer action */}
        <div className="s-sidebar-footer">
          <button
            className="s-btn-execute"
            type="button"
            onClick={() => void handleRefresh(true)}
            disabled={refreshing || wallets.length === 0}
          >
            {refreshing ? "SYNCING..." : "EXECUTE SYNC"}
          </button>
          <div className="s-sidebar-sys">
            <span className="s-sys-line">LAST SYNC: {formatRelativeTime(lastRefresh).toUpperCase()}</span>
          </div>
        </div>
      </aside>

      {/* ── MAIN CONTENT ───────────────────────────────────────────── */}
      <main className="s-main">

        {wallets.length === 0 ? (

          /* Empty state */
          <div className="s-empty">
            <p className="s-kicker">NO ACTIVE PORTFOLIO</p>
            <h2 className="s-empty-title">Register wallets to begin tracking.</h2>
            <div className="s-empty-steps">
              <div className="s-empty-step"><span>01</span><strong>WALLET INTAKE</strong><p>Add addresses in the left command rail.</p></div>
              <div className="s-empty-step"><span>02</span><strong>SCOPE MATRIX</strong><p>Limit the networks you want queried.</p></div>
              <div className="s-empty-step"><span>03</span><strong>EXECUTE SYNC</strong><p>Refresh will hydrate and persist the first checkpoints.</p></div>
            </div>
          </div>

        ) : activeView === "risk-engine" ? (
          <RiskEngineView
            wallets={wallets}
            chains={selectedChains}
            portfolioTotalUsd={totalUsd}
            defiExposureUsd={solanaDefiTotals.protocolExposureUsd}
            defiPositionCount={solanaDefiTotals.protocolPositionCount}
            defiLoading={solanaDefiTotals.loading}
            defiPositions={defiPositions}
            protocolValues={solanaDefiTotals.protocolValues}
            protocolPositionCounts={solanaDefiTotals.protocolPositionCounts}
            metrics={portfolioMetrics}
            history={history}
            benchmarks={benchmarks}
            metricsLoading={metricsLoading}
            historyLoading={historyLoading}
            benchmarkLoading={benchmarkLoading}
            metricsPartial={metricsPartial}
            historyPartial={historyPartial}
            benchmarkPartial={benchmarkPartial}
            refreshing={refreshing}
            lastRefresh={lastRefresh}
            onRefresh={() => void handleRefresh(true)}
          />
        ) : (
          <>
            {/* ── HERO: Total Net Worth ───────────────────────────────── */}
            <section className="s-hero">
              <p className="s-kicker">TOTAL NET WORTH</p>
              <div className="s-hero-row">
                <h1 className="s-hero-value">
                  {syncValueLoading ? (
                    <SyncValueLoader label="SYNCING VALUE" />
                  ) : (
                    <NetWorthValue value={totalUsd} loading={netWorthComputing} />
                  )}
                </h1>
                {heroDelta.amount !== null && (
                  <div className={`s-delta-badge ${toneClass(heroDelta.amount)}`} title={heroDelta.label}>
                    <span className="s-delta-pct">{formatPercent(heroDelta.percentage)}</span>
                    <span className="s-delta-amt mono">{formatCurrency(heroDelta.amount)}</span>
                  </div>
                )}
              </div>
              {summary && (summary.grossLiabilityUsd ?? 0) > 0 && (
                <div className="s-hero-balance-row">
                  <span className="s-hero-balance-item">GROSS {formatCurrency(summary.grossAssetUsd)}</span>
                  <span className="s-hero-balance-item tone-negative">DEBT −{formatCurrency(summary.grossLiabilityUsd)}</span>
                  <span className="s-hero-balance-item">NET {formatCurrency(summary.netUsd)}</span>
                </div>
              )}
            </section>

            {/* ── WALLET INDEX BAR ───────────────────────────────────── */}
            <section className="s-wallet-bar">
              <div className="s-wallet-bar-label">
                <span>{wallets.length} TRACKED WALLETS</span>
              </div>
              <div className="s-wallet-chips">
                {wallets.map((wallet, i) => (
                  <div key={wallet.normalizedAddress} className="s-wallet-chip">
                    <div className="s-wallet-chip-dot" style={{ background: SWATCHES[i % SWATCHES.length] }} />
                    <span className="mono">{wallet.label || shortAddress(wallet.originalInput)}</span>
                    <button
                      className="s-wallet-chip-remove"
                      type="button"
                      aria-label={`Remove ${wallet.label || shortAddress(wallet.originalInput)}`}
                      onClick={() => void handleRemoveWallet(wallet.normalizedAddress)}
                    >
                      x
                    </button>
                  </div>
                ))}
                <button className="s-wallet-chip-add" type="button">+</button>
              </div>
            </section>

            {/* ── MAIN DASHBOARD GRID ────────────────────────────────── */}
            <div className="s-grid">

              {/* LEFT COLUMN (chart + summary + inventory) */}
              <div className="s-col-main">

                {/* Performance Chart */}
                <div className="s-panel">
                  <div className="s-panel-hd">
                    <span>PORTFOLIO PERFORMANCE HISTORY</span>
                    <div className="s-panel-hd-controls">
                      <div className="chart-series-toggles">
                        <button
                          className={`chart-legend-btn ${visibleBenchmarks.bitcoin ? "is-active" : ""}`}
                          type="button"
                          disabled={!benchmarks?.bitcoin?.length}
                          onClick={() => setVisibleBenchmarks((c) => ({ ...c, bitcoin: !c.bitcoin }))}
                        >
                          <span className="chart-legend-dot" style={{ backgroundColor: "#F7931A" }} />
                          BTC
                        </button>
                        <button
                          className={`chart-legend-btn ${visibleBenchmarks.solana ? "is-active" : ""}`}
                          type="button"
                          disabled={!benchmarks?.solana?.length}
                          onClick={() => setVisibleBenchmarks((c) => ({ ...c, solana: !c.solana }))}
                        >
                          <span className="chart-legend-dot" style={{ backgroundColor: "#9945FF" }} />
                          SOL
                        </button>
                      </div>
                      <span className="s-live-badge">CURRENT SCOPE</span>
                      <div className="s-seg-group">
                        {PERIODS.map((value) => (
                          <button
                            key={value}
                            className={`s-seg-btn ${period === value ? "is-active" : ""}`}
                            type="button"
                            onClick={() => setPeriod(value)}
                          >
                            {value}
                          </button>
                        ))}
                      </div>
                    </div>
                  </div>
                  <div className="s-panel-body">
                    <Chart
                      points={chartPoints}
                      benchmarks={benchmarks}
                      visibleBenchmarks={visibleBenchmarks}
                      loading={historyLoading}
                    />
                  </div>
                </div>

                {/* Portfolio Command Summary */}
                <div className="s-panel">
                  <div className="s-panel-hd">
                    <span>PORTFOLIO SUMMARY</span>
                    <div className="s-seg-group">
                      <button
                        className={`s-seg-btn ${overviewAllocationView === "strip" ? "is-active" : ""}`}
                        type="button"
                        onClick={() => setOverviewAllocationView("strip")}
                      >STRIP</button>
                      <button
                        className={`s-seg-btn ${overviewAllocationView === "ring" ? "is-active" : ""}`}
                        type="button"
                        onClick={() => setOverviewAllocationView("ring")}
                      >RING</button>
                    </div>
                  </div>
                  <div className="s-panel-tabs">
                    <button className={`s-tab ${allocationMode === "token" ? "is-active" : ""}`} type="button" onClick={() => setAllocationMode("token")}>BY TOKEN</button>
                    <button className={`s-tab ${allocationMode === "chain" ? "is-active" : ""}`} type="button" onClick={() => setAllocationMode("chain")}>BY NETWORK</button>
                    <button className={`s-tab ${allocationMode === "wallet" ? "is-active" : ""}`} type="button" onClick={() => setAllocationMode("wallet")}>BY WALLET</button>
                  </div>
                  <div className="s-panel-body">
                    {overviewAllocationView === "ring" ? (
                      <div className="s-ring-layout">
                        <div className="s-ring-total">
                          <div className="s-mega">
                            {syncValueLoading ? (
                              <SyncValueLoader label="SYNCING VALUE" compact />
                            ) : (
                              <NetWorthValue value={totalUsd} loading={netWorthComputing} compact />
                            )}
                          </div>
                        </div>
                        <AllocationDonutChart
                          rows={summaryAllocationChartRows}
                          mode={allocationMode}
                          activeKey={hoveredAllocationKey}
                          onActiveKeyChange={setHoveredAllocationKey}
                        />
                      </div>
                    ) : (
                      <>
                        <div className="s-alloc-strip" onMouseLeave={() => setHoveredAllocationKey(null)}>
                          {summaryAllocationChartRows.map((row) => {
                            const rowKey = allocationRowKey(row);
                            const isActive = rowKey === (summaryActiveAllocationRow ? allocationRowKey(summaryActiveAllocationRow) : null);
                            return (
                              <button
                                key={rowKey}
                                type="button"
                                className={`s-alloc-strip-segment ${isActive ? "is-active" : ""}`}
                                style={{ width: `${row.share}%`, backgroundColor: row.color }}
                                aria-label={`${row.displayName} ${formatCurrency(row.valueUsd)} ${formatShare(row.share)}`}
                                onMouseEnter={() => setHoveredAllocationKey(rowKey)}
                                onFocus={() => setHoveredAllocationKey(rowKey)}
                                onBlur={() => setHoveredAllocationKey(null)}
                              />
                            );
                          })}
                        </div>
                        <div className="s-alloc-legend">
                          {summaryAllocationChartRows.map((row) => (
                            <div
                              key={allocationRowKey(row)}
                              className={`s-alloc-legend-row ${allocationRowKey(row) === (summaryActiveAllocationRow ? allocationRowKey(summaryActiveAllocationRow) : null) ? "is-active" : ""}`}
                              onMouseEnter={() => setHoveredAllocationKey(allocationRowKey(row))}
                              onMouseLeave={() => setHoveredAllocationKey(null)}
                            >
                              <span className="s-alloc-swatch" style={{ backgroundColor: row.color }} />
                              <strong className="s-alloc-name">{row.displayName}</strong>
                              <span className="s-alloc-val mono">{formatCurrency(row.valueUsd)}</span>
                              <span className="s-alloc-share mono">{formatShare(row.share)}</span>
                            </div>
                          ))}
                        </div>
                      </>
                    )}
                  </div>
                </div>

                <PortfolioMetricsBlock
                  wallets={wallets}
                  chains={selectedChains}
                  portfolioTotalUsd={totalUsd}
                  defiExposureUsd={solanaDefiTotals.protocolExposureUsd}
                  defiPositionCount={solanaDefiTotals.protocolPositionCount}
                  defiLoading={solanaDefiTotals.loading}
                  providedMetrics={portfolioMetrics}
                  providedLoading={metricsLoading}
                  providedPartial={metricsPartial}
                />

                <AiAdvisorBlock wallets={wallets} chains={selectedChains} />

                <DefiPositionsBlock wallets={wallets} onSolanaTotalsChange={setSolanaDefiTotals} />

                {/* Asset Inventory */}
                <div className="s-panel">
                  <div className="s-panel-hd">
                    <span>ASSET INVENTORY TOP {assetInventoryRows.length}</span>
                    <span className="s-badge">{assetInventoryRows.length} ROWS</span>
                  </div>
                  <div className="s-table-shell">
                    <table className="s-table">
                      <thead>
                        <tr>
                          <th>ASSET</th>
                          <th>NETWORK</th>
                          <th>TYPE</th>
                          <th className="align-right">BALANCE</th>
                          <th className="align-right">PRICE</th>
                          <th className="align-right">VALUE</th>
                          <th className="align-right">WEIGHT</th>
                        </tr>
                      </thead>
                      <tbody>
                        {assetInventoryRows.map((asset) => (
                          <tr key={asset.key}>
                            <td data-label="Asset">
                              <div className="s-asset-cell">
                                <div className="s-asset-icon">{asset.symbol.slice(0, 1)}</div>
                                <div>
                                  <strong>{asset.symbol}</strong>
                                  <span>{asset.name || "Unknown"}</span>
                                </div>
                              </div>
                            </td>
                            <td data-label="Network">
                              <NetworkBreakdownTag asset={asset} />
                            </td>
                            <td data-label="Type"><span className="s-tag">{asset.typeLabel}</span></td>
                            <td data-label="Balance" className="align-right mono">{formatQuantity(asset.quantity)}</td>
                            <td data-label="Price" className="align-right mono">{formatCurrency(asset.priceUsd)}</td>
                            <td data-label="Value" className="align-right mono s-bold">{formatCurrency(asset.valueUsd)}</td>
                            <td data-label="Weight" className={`align-right mono ${toneClass(null)}`}>{formatShare(shareOf(asset.valueUsd, totalUsd))}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </div>

              </div>{/* end s-col-main */}

              {/* RIGHT COLUMN (exposure + allocation + readout) */}
              <div className="s-col-side">

                <div className="s-panel">
                  <div className="s-panel-hd s-panel-hd-blue">
                    <span>NETWORK EXPOSURE</span>
                    <div className="s-panel-hd-controls">
                      <span className="s-badge mono">TOP {Math.min(chainAllocationRows.length, 6)}/{chainAllocationRows.length}</span>
                    </div>
                  </div>
                  <div className="s-panel-body s-exposure-list">
                    {chainAllocationRows.slice(0, 6).map((item, i) => {
                      const share = shareOf(item.valueUsd, totalUsd);
                      return (
                        <div key={item.network} className="s-exposure-row" title={`${item.displayName}: ${formatCurrency(item.valueUsd)}`}>
                          <div className="s-exposure-meta">
                            <span className="s-exposure-name">{item.displayName.toUpperCase()}</span>
                            <span className="mono s-exposure-pct">{formatShare(share)}</span>
                          </div>
                          <div className="s-bar-track">
                            <div
                              className="s-bar-fill"
                              style={{
                                width: `${Math.max(share, 0.5)}%`,
                                backgroundColor: SWATCHES[i % SWATCHES.length],
                              }}
                            />
                          </div>
                        </div>
                      );
                    })}
                  </div>
                </div>

                {/* Allocation Detail */}
                <div className="s-panel">
                  <div className="s-panel-hd">
                    <span>ALLOCATION DETAIL</span>
                    <div className="s-panel-hd-controls">
                      <span className="s-badge">{allocationMeta.label.toUpperCase()}</span>
                      <span className="s-badge mono">TOP {allocationChartRows.length}/{allocationRows.length}</span>
                    </div>
                  </div>
                  <div className="s-panel-body">
                    <AllocationDetailList
                      rows={allocationChartRows}
                      mode={allocationMode}
                      activeKey={activeAllocationRow ? allocationRowKey(activeAllocationRow) : null}
                      onActiveKeyChange={setHoveredAllocationKey}
                    />
                  </div>
                </div>

                {/* System Readout */}
                <div className="s-panel">
                  <div className="s-panel-hd">
                    <span>SYSTEM READOUT</span>
                    <span className="s-badge">SERVER</span>
                  </div>
                  <div className="s-panel-body s-readout-list">
                    <div className="s-readout-row">
                      <span className="s-readout-label">TOP NETWORK</span>
                      <strong className="s-readout-val">{topNetwork}</strong>
                    </div>
                    <div className="s-readout-row">
                      <span className="s-readout-label">TOP WALLET</span>
                      <strong className="s-readout-val">{topWallet}</strong>
                    </div>
                    <div className="s-readout-row">
                      <span className="s-readout-label">TOP ASSET</span>
                      <strong className="s-readout-val">{assetInventoryRows[0]?.symbol ?? "N/A"}</strong>
                    </div>
                    <div className="s-readout-row">
                      <span className="s-readout-label">NETWORKS SCOPE</span>
                      <strong className="s-readout-val mono">{selectedChains.length}</strong>
                    </div>
                    <div className="s-readout-row">
                      <span className="s-readout-label">DELTA PERIOD</span>
                      <strong className="s-readout-val mono">{chartDelta.label}</strong>
                    </div>
                  </div>
                </div>

              </div>{/* end s-col-side */}
            </div>{/* end s-grid */}
          </>
        )}
      </main>

      {/* ── FOOTER STATUS BAR ──────────────────────────────────────── */}
      <footer className="s-footer">
        <div className="s-footer-left">
          <span className="s-dot" />
          <span>SYSTEM STABLE</span>
          <span className="s-sep">|</span>
          <span>{wallets.length} TRACKED WALLETS</span>
        </div>
      </footer>

    </div>
  );
}

function MetricTile({ label, value, tone }: { label: string; value: string; tone?: number | null }) {
  const valueClassName = ["metric-value", toneClass(tone ?? null)];
  if (value.length > 10) {
    valueClassName.push("is-compact");
  }

  return (
    <article className="metric-tile">
      <span className="metric-label">{label}</span>
      <strong className={valueClassName.filter(Boolean).join(" ")}>{value}</strong>
    </article>
  );
}

function NetworkBreakdownTag({ asset }: { asset: AssetInventoryRow }) {
  if (asset.networkBreakdown.length <= 1) {
    return <span className="s-tag">{asset.networkLabel}</span>;
  }

  return (
    <span className="s-network-breakdown-tag">
      <span className="s-tag">{asset.networkLabel}</span>
      <span className="s-network-breakdown-popover" role="tooltip">
        <strong>{asset.symbol} NETWORK BREAKDOWN</strong>
        {asset.networkBreakdown.map((item) => (
          <span key={item.network} className="s-network-breakdown-row">
            <span>{item.label}</span>
            <span className="mono">{formatShare(item.share)}</span>
            <span className="mono">{formatCurrency(item.valueUsd)}</span>
          </span>
        ))}
      </span>
    </span>
  );
}

function Chart({ points, benchmarks, visibleBenchmarks, loading }: {
  points: ChartPoint[];
  benchmarks: BenchmarkData | null;
  visibleBenchmarks: { bitcoin: boolean; solana: boolean };
  loading: boolean;
}) {
  const viewportRef = useRef<HTMLDivElement>(null);
  const [hoveredPoint, setHoveredPoint] = useState<{
    point: ChartPoint;
    indexValue: number;
    x: number;
    y: number;
    width: number;
    height: number;
  } | null>(null);

  if (points.length === 0 && loading) {
    return (
      <div className="chart-empty chart-loading">
        <SyncValueLoader label="SYNCING DAILY CHECKPOINTS" compact />
      </div>
    );
  }

  if (points.length === 0) {
    return <div className="chart-empty"><strong>No history in scope</strong><p>Refresh to hydrate the first server-side baseline.</p></div>;
  }

  const width = 820;
  const height = 248;
  const padLeft = 84;
  const padRight = 52;
  const padTop = 28;
  const padBottom = 34;
  const firstPortfolioValue = points[0]?.value ?? 1;
  const portfolioSeries = buildPortfolioIndexSeries(points);
  const bitcoinSeries = benchmarks ? buildBenchmarkSeries(benchmarks.bitcoin, points) : [];
  const solanaSeries = benchmarks ? buildBenchmarkSeries(benchmarks.solana, points) : [];
  const visibleIndexValues = [
    ...portfolioSeries.map((point) => point.indexValue),
    ...(visibleBenchmarks.bitcoin ? bitcoinSeries.map((point) => point.indexValue) : []),
    ...(visibleBenchmarks.solana ? solanaSeries.map((point) => point.indexValue) : []),
  ];
  const values = visibleIndexValues.length > 0 ? visibleIndexValues : portfolioSeries.map((point) => point.indexValue);
  const min = Math.min(...values);
  const max = Math.max(...values);
  const padding = min === max
    ? Math.max(Math.abs(min) * 0.08, 1)
    : Math.max((max - min) * 0.14, Math.max(Math.abs(max) * 0.02, 1));
  const floor = Math.max(0, min - padding);
  const ceil = max + padding;
  const range = ceil - floor;
  const yTicks = Array.from({ length: 5 }, (_, index) => {
    const ratio = index / 4;
    const y = padTop + ((height - padTop - padBottom) / 4) * index;
    const value = ceil - ratio * range;
    return {
      key: `${index}-${value}`,
      y,
      value,
    };
  });
  const coords = portfolioSeries.map((point, index) => toChartCoord(point, index, portfolioSeries.length, padLeft, padRight, padTop, padBottom, width, height, floor, range));
  const bitcoinCoords = bitcoinSeries.map((point, index) => toChartCoord(point, index, bitcoinSeries.length, padLeft, padRight, padTop, padBottom, width, height, floor, range));
  const solanaCoords = solanaSeries.map((point, index) => toChartCoord(point, index, solanaSeries.length, padLeft, padRight, padTop, padBottom, width, height, floor, range));
  const interactiveCoords = coords.map((point, index) => {
    const previousX = index === 0 ? padLeft : (coords[index - 1].x + point.x) / 2;
    const nextX = index === coords.length - 1 ? width - padRight : (point.x + coords[index + 1].x) / 2;
    return {
      ...point,
      hitStart: previousX,
      hitWidth: Math.max(nextX - previousX, 18),
    };
  });
  const linePath = buildSvgPath(coords);
  const bitcoinPath = buildSvgPath(bitcoinCoords);
  const solanaPath = buildSvgPath(solanaCoords);
  const areaPath = `${linePath} L ${coords[coords.length - 1].x.toFixed(2)} ${(height - padBottom).toFixed(2)} L ${coords[0].x.toFixed(2)} ${(height - padBottom).toFixed(2)} Z`;
  const step = Math.max(1, Math.ceil(points.length / 5));

  const focusPoint = (point: typeof coords[number]) => {
    const rect = viewportRef.current?.getBoundingClientRect();
    if (!rect) {
      return;
    }
    setHoveredPoint({
      point,
      indexValue: point.indexValue,
      x: (point.x / width) * rect.width,
        y: (point.y / height) * rect.height,
      width: rect.width,
      height: rect.height,
    });
  };

  const hoveredCoord = hoveredPoint
    ? coords.find((point) => point.localDate === hoveredPoint.point.localDate && point.current === hoveredPoint.point.current) ?? null
    : null;

  return (
    <div className="chart-viewport" ref={viewportRef}>
      <svg
        viewBox={`0 0 ${width} ${height}`}
        className="chart-svg"
        aria-label="Portfolio chart"
        onMouseLeave={() => setHoveredPoint(null)}
      >
        <rect x={padLeft} y={padTop} width={width - padLeft - padRight} height={height - padTop - padBottom} className="chart-frame" />
        <line x1={padLeft} y1={padTop} x2={padLeft} y2={height - padBottom} className="chart-axis-line" />
        <line x1={padLeft} y1={height - padBottom} x2={width - padRight} y2={height - padBottom} className="chart-axis-line" />
        {yTicks.map((tick) => {
          return (
            <g key={tick.key}>
              <line x1={padLeft} y1={tick.y} x2={width - padRight} y2={tick.y} className="chart-grid-line" />
              <text x={padLeft - 10} y={tick.y + 4} textAnchor="end" className="chart-axis-label">
                {formatAxisCurrency((tick.value / 100) * firstPortfolioValue)}
              </text>
              <text x={width - padRight + 8} y={tick.y + 4} textAnchor="start" className="chart-axis-label chart-axis-label-idx">
                {tick.value.toFixed(0)}
              </text>
            </g>
          );
        })}
        <line x1={width - padRight} y1={padTop} x2={width - padRight} y2={height - padBottom} className="chart-axis-line" />
        <path d={areaPath} className="chart-area" />
        {visibleBenchmarks.bitcoin && bitcoinPath ? <path d={bitcoinPath} className="chart-line is-benchmark" style={{ stroke: "#F7931A" }} /> : null}
        {visibleBenchmarks.solana && solanaPath ? <path d={solanaPath} className="chart-line is-benchmark" style={{ stroke: "#9945FF" }} /> : null}
        <path d={linePath} className={`chart-line ${coords[coords.length - 1].indexValue >= coords[0].indexValue ? "is-up" : "is-down"}`} />
        {interactiveCoords.map((point) => (
          <rect
            key={`${point.localDate}-zone`}
            x={point.hitStart}
            y={padTop}
            width={point.hitWidth}
            height={height - padTop - padBottom}
            className="chart-hit-zone"
            onMouseEnter={() => focusPoint(point)}
            onMouseMove={() => focusPoint(point)}
          />
        ))}
        {hoveredCoord ? (
          <g className="chart-focus-layer">
            <line x1={hoveredCoord.x} y1={padTop} x2={hoveredCoord.x} y2={height - padBottom} className="chart-focus-line" />
            <line x1={padLeft} y1={hoveredCoord.y} x2={width - padRight} y2={hoveredCoord.y} className="chart-focus-line is-horizontal" />
            <circle cx={hoveredCoord.x} cy={hoveredCoord.y} r={7} className="chart-focus-ring" />
          </g>
        ) : null}
        {coords.map((point, index) => (
          <g key={point.localDate}>
            <circle
              cx={point.x}
              cy={point.y}
              r={point.current ? 4.5 : 3.25}
              className={`chart-dot ${point.current ? "is-current" : ""}`}
              pointerEvents="none"
            />
            {(index % step === 0 || index === coords.length - 1) && <text x={point.x} y={height - 8} textAnchor="middle" className="chart-label">{point.label}</text>}
          </g>
        ))}
      </svg>
      {hoveredPoint ? (
        <div
          className="chart-tooltip"
          style={{
            left: hoveredPoint.x > hoveredPoint.width * 0.58
              ? `${Math.max(hoveredPoint.x - 208, 12)}px`
              : `${Math.min(hoveredPoint.x + 18, hoveredPoint.width - 208)}px`,
            top: `${Math.max(Math.min(hoveredPoint.y - 78, hoveredPoint.height - 96), 12)}px`,
          }}
        >
          <span>{hoveredPoint.point.tooltipContext}</span>
          <strong>{formatCurrency(hoveredPoint.point.value)}</strong>
          <p>{hoveredPoint.point.timestampLabel}</p>
          <p className="chart-tooltip-idx">IDX {hoveredPoint.indexValue.toFixed(1)}</p>
        </div>
      ) : null}
      <div className="chart-idx-explainer" aria-label="Performance index info">
        <span>IDX</span>
        <div className="chart-idx-popover" role="tooltip">
          <strong>Performance Index</strong>
          <p>Rebased to 100 at the start of the period. IDX 115 = portfolio up 15% since start. Allows you to compare your portfolio against BTC and SOL on the same scale.</p>
        </div>
      </div>
    </div>
  );
}

function ChartLegendButton({
  label,
  color,
  active,
  disabled = false,
  onClick,
}: {
  label: string;
  color: string;
  active: boolean;
  disabled?: boolean;
  onClick: () => void;
}) {
  return (
    <button
      className={`chart-legend-btn ${active ? "is-active" : ""}`}
      type="button"
      disabled={disabled}
      onClick={onClick}
      aria-pressed={active}
    >
      <span className="chart-legend-dot" style={{ backgroundColor: color }} />
      <span>{label}</span>
    </button>
  );
}

function buildPortfolioIndexSeries(points: ChartPoint[]) {
  const firstValue = points[0]?.value;
  const base = firstValue && firstValue > 0 ? firstValue : 1;
  return points.map((point) => ({
    ...point,
    indexValue: point.value > 0 ? (point.value / base) * 100 : 100,
  }));
}

function buildBenchmarkSeries(points: BenchmarkData["bitcoin"], chartPoints: ChartPoint[]) {
  if (points.length === 0 || chartPoints.length === 0) {
    return [];
  }

  const benchmarks = points
    .map((point) => ({
      localDate: new Date(point.timestamp * 1000).toISOString().slice(0, 10),
      rawIndex: point.index,
    }))
    .sort((left, right) => left.localDate.localeCompare(right.localDate));

  if (benchmarks.length === 0) return [];

  const valueAtOrBefore = (date: string): number | null => {
    let result: number | null = null;
    for (const b of benchmarks) {
      if (b.localDate <= date) result = b.rawIndex;
      else break;
    }
    return result;
  };

  const baseValue = valueAtOrBefore(chartPoints[0].localDate) ?? benchmarks[0].rawIndex;
  if (baseValue <= 0) return [];

  return chartPoints.map((cp) => {
    const value = valueAtOrBefore(cp.localDate) ?? baseValue;
    return {
      localDate: cp.localDate,
      label: cp.label,
      indexValue: (value / baseValue) * 100,
    };
  });
}

function toChartCoord<T extends { indexValue: number }>(
  point: T,
  index: number,
  count: number,
  padLeft: number,
  padRight: number,
  padTop: number,
  padBottom: number,
  width: number,
  height: number,
  floor: number,
  range: number,
) {
  const x = count === 1 ? (padLeft + width - padRight) / 2 : padLeft + (index * (width - padLeft - padRight)) / Math.max(1, count - 1);
  const y = height - padBottom - ((point.indexValue - floor) / range) * (height - padTop - padBottom);
  return { ...point, x, y };
}

function buildSvgPath(points: Array<{ x: number; y: number }>): string {
  return points.map((point, index) => `${index === 0 ? "M" : "L"} ${point.x.toFixed(2)} ${point.y.toFixed(2)}`).join(" ");
}

function AllocationDonutChart({
  rows,
  mode,
  activeKey,
  onActiveKeyChange,
}: {
  rows: AllocationChartRow[];
  mode: AllocationMode;
  activeKey: string | null;
  onActiveKeyChange: (key: string | null) => void;
}) {
  const leadRow = rows[0] ?? null;
  const activeRow = rows.find((row) => allocationRowKey(row) === activeKey) ?? leadRow;
  const modeMeta = allocationModeMeta(mode);

  if (rows.length === 0) {
    return (
      <div className="allocation-empty">
        <strong>No priced allocation available</strong>
        <p>Sync a funded wallet to render a distribution view for {modeMeta.plural}.</p>
      </div>
    );
  }

  let offset = 0;
  const segments = rows.map((row) => {
    const gap = rows.length > 1 && row.share > 1 ? 0.8 : 0;
    const segment = { ...row, offset, dashLength: Math.max(row.share - gap, 0) };
    offset += row.share;
    return segment;
  });

  return (
    <div className="overview-ring-stage">
      <div className="allocation-chart-shell allocation-chart-shell-hero">
        <svg
          viewBox="0 0 260 260"
          className="allocation-donut"
          aria-label={`Portfolio allocation by ${modeMeta.plural}`}
          onMouseLeave={() => {
            onActiveKeyChange(null);
          }}
        >
          <circle cx="130" cy="130" r="88" className="allocation-donut-track" />
          {segments.map((row) => {
            const rowKey = allocationRowKey(row);
            const isActive = rowKey === allocationRowKey(activeRow);

            return (
              <circle
                key={rowKey}
                cx="130"
                cy="130"
                r="88"
                pathLength={100}
                stroke={row.color}
                strokeDasharray={`${row.dashLength} 100`}
                strokeDashoffset={-row.offset}
                className={`allocation-donut-segment ${isActive ? "is-active" : ""}`}
                transform="rotate(-90 130 130)"
                tabIndex={0}
                aria-label={`${row.displayName} ${formatCurrency(row.valueUsd)} ${formatShare(row.share)}`}
                onMouseEnter={() => onActiveKeyChange(rowKey)}
                onFocus={() => onActiveKeyChange(rowKey)}
                onBlur={() => onActiveKeyChange(null)}
              />
            );
          })}
        </svg>
        <div className="allocation-center">
          <span className="allocation-center-label">{activeKey ? `Focused ${modeMeta.singular}` : `Largest ${modeMeta.singular}`}</span>
          <strong>{activeRow?.displayName ?? "Waiting"}</strong>
          <div className="allocation-center-value">{activeRow ? formatCurrency(activeRow.valueUsd) : "No data"}</div>
          <span className="allocation-center-share">{activeRow ? formatShare(activeRow.share) : "0.00%"}</span>
          {activeRow?.sourceBreakdown && activeRow.sourceBreakdown.length > 1 ? (
            <div className="allocation-center-sources">
              {activeRow.sourceBreakdown.map((source) => (
                <span key={source.label}>
                  {source.label.replace(" balance", "")} {formatCurrency(source.valueUsd)}
                </span>
              ))}
            </div>
          ) : null}
        </div>
      </div>
    </div>
  );
}

function AllocationDetailList({
  rows,
  mode,
  activeKey,
  onActiveKeyChange,
}: {
  rows: AllocationChartRow[];
  mode: AllocationMode;
  activeKey: string | null;
  onActiveKeyChange: (key: string | null) => void;
}) {
  const modeMeta = allocationModeMeta(mode);

  if (rows.length === 0) {
    return (
      <div className="allocation-empty allocation-empty-compact">
        <strong>No priced allocation available</strong>
        <p>Sync a funded wallet to populate the ranked {modeMeta.plural} detail surface.</p>
      </div>
    );
  }

  return (
    <div className="allocation-detail-list">
      {rows.map((row, index) => {
        const rowKey = allocationRowKey(row);
        const isActive = rowKey === activeKey;

        return (
          <article
            key={rowKey}
            className={`allocation-legend-row ${isActive ? "is-active" : ""}`}
            onMouseEnter={() => onActiveKeyChange(rowKey)}
            onMouseLeave={() => onActiveKeyChange(null)}
          >
            <div className="allocation-legend-rank">{String(index + 1).padStart(2, "0")}</div>
            <div className="allocation-legend-main">
              <div className="allocation-legend-label">
                <span className="legend-swatch" style={{ backgroundColor: row.color }} />
                <strong>{row.displayName}</strong>
              </div>
              {row.grouped ? (
                <p className="allocation-legend-copy">
                  {`${row.groupedCount} smaller ${modeMeta.plural} combined into a single bucket.`}
                </p>
              ) : null}
            </div>
            <div className="allocation-legend-values">
              <strong>{formatShare(row.share)}</strong>
              <span className="mono">{formatCurrency(row.valueUsd)}</span>
            </div>
          </article>
        );
      })}
    </div>
  );
}

function SyncValueLoader({ label, compact = false }: { label: string; compact?: boolean }) {
  return (
    <span className={`s-sync-value ${compact ? "is-compact" : ""}`} role="status" aria-live="polite">
      <span className="s-sync-value-track">
        <span className="s-sync-value-fill" />
      </span>
      <span className="s-sync-value-label mono">{label}</span>
    </span>
  );
}

function NetWorthValue({
  value,
  loading,
  compact = false,
}: {
  value: number;
  loading: boolean;
  compact?: boolean;
}) {
  return (
    <span className={`s-net-worth-value ${compact ? "is-compact" : ""}`}>
      <span className="s-net-worth-amount">{formatCurrency(value)}</span>
      {loading ? <NetWorthProgressPill label="CALCULATING" /> : null}
    </span>
  );
}

function NetWorthProgressPill({ label }: { label: string }) {
  return (
    <span className="s-net-worth-pending" role="status" aria-live="polite">
      <span className="s-net-worth-pulse" aria-hidden="true" />
      <span>{label}</span>
    </span>
  );
}

function buildChartPoints(historyPoints: PortfolioHistoryPoint[], currentTotalUsd: number | null, period: Period): ChartPoint[] {
  let points = historyPoints
    .slice()
    .sort((left, right) => left.localDate.localeCompare(right.localDate))
    .map((point) => ({
    label: formatShortDate(point.localDate),
    localDate: point.localDate,
    value: point.totalUsd,
    current: false,
    timestampLabel: formatHistoryPointTimestamp(point.localDate, false),
    tooltipContext: "Daily checkpoint",
  }));

  if (currentTotalUsd !== null) {
    const currentDate = new Date().toISOString().slice(0, 10);
    const currentPoint = {
      label: formatShortDate(currentDate),
      localDate: currentDate,
      value: currentTotalUsd,
      current: true,
      timestampLabel: formatHistoryPointTimestamp(currentDate, true),
      tooltipContext: "Current wallet/network scope",
    };
    const existingIndex = points.findIndex((point) => point.localDate === currentDate);
    if (existingIndex >= 0) {
      points[existingIndex] = currentPoint;
    } else {
      points = [...points, currentPoint];
    }
  }

  if (period === "24h") {
    return points.slice(-Math.min(points.length, 7));
  }
  if (period === "7d") return points.slice(-8);
  if (period === "30d") return points.slice(-31);
  return points;
}

function buildAllocationChartRows(rows: AllocationInputRow[], totalUsd: number): AllocationChartRow[] {
  if (totalUsd <= 0) {
    return [];
  }

  const sortedRows = rows
    .filter((row) => row.valueUsd > 0)
    .sort((left, right) => right.valueUsd - left.valueUsd);

  if (sortedRows.length === 0) {
    return [];
  }

  const visibleRows = sortedRows.slice(0, MAX_ALLOCATION_SLICES).map((row, index) => ({
    ...row,
    share: shareOf(row.valueUsd, totalUsd),
    color: SWATCHES[index % SWATCHES.length],
    grouped: false,
    groupedCount: 0,
  }));

  const groupedRows = sortedRows.slice(MAX_ALLOCATION_SLICES);
  if (groupedRows.length === 0) {
    return visibleRows;
  }

  const groupedValueUsd = groupedRows.reduce((sum, row) => sum + row.valueUsd, 0);

  return [
    ...visibleRows,
    {
      network: "__other__",
      displayName: "Other",
      valueUsd: groupedValueUsd,
      share: shareOf(groupedValueUsd, totalUsd),
      color: OTHER_SWATCH,
      grouped: true,
      groupedCount: groupedRows.length,
    },
  ];
}

function buildTokenAllocationRows(
  assets: AssetRow[],
  defiTokenValues: Record<string, number>,
): AllocationInputRow[] {
  const rows = new Map<string, AllocationInputRow>();

  for (const row of groupAllocationsByToken(assets)) {
    const key = canonicalTokenAllocationKey(row.displayName || row.network);
    rows.set(key, {
      network: key,
      displayName: row.displayName,
      valueUsd: row.valueUsd,
      sourceBreakdown: row.valueUsd > 0 ? [{ label: "Spot balance", valueUsd: row.valueUsd }] : [],
    });
  }

  for (const [symbol, valueUsd] of Object.entries(defiTokenValues)) {
    if (valueUsd <= 0) {
      continue;
    }

    const key = canonicalTokenAllocationKey(symbol);
    const existing = rows.get(key);
    if (existing) {
      rows.set(key, {
        ...existing,
        valueUsd: existing.valueUsd + valueUsd,
        sourceBreakdown: mergeAllocationSources(existing.sourceBreakdown, "DeFi positions", valueUsd),
      });
      continue;
    }

    rows.set(key, {
      network: key,
      displayName: key,
      valueUsd,
      sourceBreakdown: [{ label: "DeFi positions", valueUsd }],
    });
  }

  return [...rows.values()].sort((left, right) => right.valueUsd - left.valueUsd);
}

function buildBackendDefiTokenValues(
  positions: Array<Pick<LendingPositionResponse, "underlyingSymbol" | "netUsd" | "alreadyCountedInPortfolio">>,
): Record<string, number> {
  return positions.reduce<Record<string, number>>((values, position) => {
    if (position.alreadyCountedInPortfolio) {
      return values;
    }

    const valueUsd = position.netUsd ?? 0;
    if (valueUsd <= 0) {
      return values;
    }

    const symbol = canonicalTokenAllocationKey(position.underlyingSymbol);
    values[symbol] = (values[symbol] ?? 0) + valueUsd;
    return values;
  }, {});
}

function mergeValueMaps(...maps: Array<Record<string, number>>): Record<string, number> {
  return maps.reduce<Record<string, number>>((merged, map) => {
    for (const [rawKey, valueUsd] of Object.entries(map)) {
      const key = canonicalTokenAllocationKey(rawKey);
      merged[key] = (merged[key] ?? 0) + valueUsd;
    }
    return merged;
  }, {});
}

function mergeAllocationSources(
  sources: AllocationSourceBreakdown[] | undefined,
  label: string,
  valueUsd: number,
): AllocationSourceBreakdown[] {
  const next = [...(sources ?? [])];
  const existing = next.find((source) => source.label === label);
  if (existing) {
    existing.valueUsd += valueUsd;
  } else {
    next.push({ label, valueUsd });
  }
  return next.filter((source) => source.valueUsd > 0);
}

function canonicalTokenAllocationKey(value: string): string {
  const symbol = value.trim().toUpperCase();
  if (symbol === "USDC.E") return "USDC";
  if (symbol === "WETH") return "ETH";
  return symbol || "UNKNOWN";
}

function buildAssetInventoryRows(assets: AssetRow[]): AssetInventoryRow[] {
  type MutableInventoryRow = Omit<AssetInventoryRow, "priceUsd" | "networkLabel" | "typeLabel"> & {
    networkSet: Set<string>;
    valueByNetwork: Map<string, number>;
    hasNative: boolean;
    hasWrapped: boolean;
    hasPlatform: boolean;
    hasToken: boolean;
  };

  const rows = new Map<string, MutableInventoryRow>();

  for (const asset of assets) {
    const canonical = canonicalAsset(asset);
    const existing = rows.get(canonical.key);
    if (existing) {
      existing.quantity += asset.quantity;
      existing.valueUsd += asset.valueUsd;
      existing.networkSet.add(asset.network);
      existing.valueByNetwork.set(asset.network, (existing.valueByNetwork.get(asset.network) ?? 0) + asset.valueUsd);
      existing.hasNative = existing.hasNative || asset.nativeToken;
      existing.hasWrapped = existing.hasWrapped || canonical.wrapped;
      existing.hasPlatform = existing.hasPlatform || asset.network === "hyperliquid";
      existing.hasToken = existing.hasToken || (!asset.nativeToken && !canonical.wrapped);
    } else {
      rows.set(canonical.key, {
        key: canonical.key,
        symbol: canonical.symbol,
        name: canonical.name,
        quantity: asset.quantity,
        valueUsd: asset.valueUsd,
        networks: [],
        networkBreakdown: [],
        networkSet: new Set([asset.network]),
        valueByNetwork: new Map([[asset.network, asset.valueUsd]]),
        hasNative: asset.nativeToken,
        hasWrapped: canonical.wrapped,
        hasPlatform: asset.network === "hyperliquid",
        hasToken: !asset.nativeToken && !canonical.wrapped,
      });
    }
  }

  return [...rows.values()]
    .map((row) => {
      const networks = [...row.networkSet].sort();
      const networkBreakdown = [...row.valueByNetwork.entries()]
        .map(([network, valueUsd]) => ({
          network,
          label: displayNetwork(network),
          valueUsd,
          share: shareOf(valueUsd, row.valueUsd),
        }))
        .sort((left, right) => right.valueUsd - left.valueUsd);
      return {
        key: row.key,
        symbol: row.symbol,
        name: row.name,
        quantity: row.quantity,
        priceUsd: row.quantity > 0 ? row.valueUsd / row.quantity : 0,
        valueUsd: row.valueUsd,
        networks,
        networkBreakdown,
        networkLabel: networks.length === 1 ? displayNetwork(networks[0]) : `${networks.length} NETWORKS`,
        typeLabel: inventoryTypeLabel(row),
      };
    })
    .sort((left, right) => right.valueUsd - left.valueUsd);
}

function inventoryTypeLabel(row: {
  hasNative: boolean;
  hasWrapped: boolean;
  hasPlatform: boolean;
  hasToken: boolean;
}): string {
  if (row.hasPlatform && !row.hasNative && !row.hasWrapped && !row.hasToken) return "PLATFORM";
  if (row.hasNative && row.hasWrapped) return "NATIVE + WRAPPED";
  if (row.hasNative) return "NATIVE";
  if (row.hasWrapped) return "WRAPPED";
  return "TOKEN";
}

function buildChainAllocationRows(
  allocations: ChainAllocation[],
  defiChainValues: Record<string, number>,
): ChainAllocation[] {
  const rows = new Map<string, ChainAllocation>();

  for (const allocation of allocations) {
    const key = normalizeNetworkKey(allocation.network);
    rows.set(key, { ...allocation, network: key });
  }

  for (const [chain, valueUsd] of Object.entries(defiChainValues)) {
    if (valueUsd <= 0) continue;
    const key = normalizeNetworkKey(chain);
    const existing = rows.get(key);
    rows.set(key, {
      network: key,
      displayName: existing?.displayName ?? networkDisplayName(key),
      valueUsd: (existing?.valueUsd ?? 0) + valueUsd,
    });
  }

  return [...rows.values()].sort((left, right) => right.valueUsd - left.valueUsd);
}

function sumAllocationForNetwork(allocations: ChainAllocation[], network: string): number {
  const target = normalizeNetworkKey(network);
  return allocations.reduce((sum, allocation) => {
    const key = normalizeNetworkKey(allocation.network);
    return key === target ? sum + allocation.valueUsd : sum;
  }, 0);
}

function normalizeNetworkKey(network: string): string {
  return network.trim().toLowerCase().replace(/-mainnet$/, "");
}

function networkDisplayName(network: string): string {
  const names: Record<string, string> = {
    solana: "Solana",
    ethereum: "Ethereum",
    arbitrum: "Arbitrum",
    base: "Base",
    polygon: "Polygon",
    optimism: "Optimism",
    "binance-smart-chain": "BNB Chain",
    avalanche: "Avalanche",
    fantom: "Fantom",
  };
  return names[network] ?? network.replace(/-/g, " ").replace(/\b\w/g, (c) => c.toUpperCase());
}

function buildWalletAllocationRows(
  walletAllocations: PortfolioSummaryResponse["walletAllocations"],
  wallets: WalletRecord[],
  solanaDefiWalletValues: Record<string, number>,
): ChainAllocation[] {
  const labelByAddress = new Map(
    wallets.map((wallet) => {
      const displayAddress = wallet.originalInput || wallet.normalizedAddress;
      return [
        walletKey(wallet.normalizedAddress),
        wallet.label?.trim() ? `${wallet.label.trim()} / ${shortAddress(displayAddress)}` : shortAddress(displayAddress),
      ];
    }),
  );
  const valueByWallet = new Map<string, number>();

  for (const allocation of walletAllocations) {
    valueByWallet.set(walletKey(allocation.walletAddress), allocation.valueUsd);
  }

  for (const [walletAddress, valueUsd] of Object.entries(solanaDefiWalletValues)) {
    const key = walletKey(walletAddress);
    valueByWallet.set(key, (valueByWallet.get(key) ?? 0) + valueUsd);
  }

  return [...valueByWallet.entries()]
    .map(([walletAddress, valueUsd]) => ({
      network: walletAddress,
      displayName: labelByAddress.get(walletAddress) ?? shortAddress(walletAddress),
      valueUsd,
    }))
    .sort((left, right) => right.valueUsd - left.valueUsd);
}

function parseWalletInput(value: string): string[] {
  return value
    .split(/[\s,;]+/)
    .map((entry) => entry.trim())
    .filter(Boolean);
}

function walletKey(address: string): string {
  return isValidEvmAddress(address) ? address.toLowerCase() : address;
}

function reconcileSelectedChains(
  supportedChains: ChainOption[],
  savedSelectedChains: string | null,
  savedChainCatalog: string | null,
): string[] {
  const supportedIds = supportedChains.map((chain) => chain.id);
  if (!savedSelectedChains) {
    return supportedIds;
  }

  const supportedIdSet = new Set(supportedIds);
  const savedIds = savedSelectedChains
    .split(",")
    .map((chain) => chain.trim())
    .filter((chain) => supportedIdSet.has(chain));

  if (savedIds.length === 0) {
    return supportedIds;
  }

  const selected = new Set(savedIds);
  const previousCatalogIds = savedChainCatalog
    ?.split("|")
    .map((chain) => chain.trim())
    .filter(Boolean);

  if (previousCatalogIds && previousCatalogIds.length > 0) {
    const selectedPreviousCatalog = previousCatalogIds.every((chain) => selected.has(chain));
    if (selectedPreviousCatalog) {
      for (const chainId of supportedIds) {
        if (!previousCatalogIds.includes(chainId)) {
          selected.add(chainId);
        }
      }
    }
  } else if (supportedIdSet.has("solana")) {
    const nonSolanaIds = supportedIds.filter((chain) => chain !== "solana");
    const selectedAllPreviousEvmChains = nonSolanaIds.length > 0 && nonSolanaIds.every((chain) => selected.has(chain));
    if (selectedAllPreviousEvmChains) {
      selected.add("solana");
    }
  }

  return supportedIds.filter((chain) => selected.has(chain));
}

function catalogSignature(chains: ChainOption[]): string {
  return chains.map((chain) => chain.id).sort().join("|");
}

function allocationModeMeta(mode: AllocationMode): {
  singular: string;
  plural: string;
  label: string;
} {
  if (mode === "token") {
    return {
      singular: "asset",
      plural: "assets",
      label: "Asset",
    };
  }

  if (mode === "wallet") {
    return {
      singular: "wallet",
      plural: "wallets",
      label: "Wallet",
    };
  }

  return {
    singular: "network",
    plural: "networks",
    label: "Network",
  };
}

function allocationRowKey(row: Pick<AllocationChartRow, "network" | "displayName"> | null): string {
  if (!row) {
    return "__empty__";
  }

  return `${row.network}::${row.displayName}`;
}

type ProtocolGroup = {
  protocolKey: string;
  protocolName: string;
  netUsd: number;
  positions: Array<{
    positionId: string;
    protocolKey: string;
    protocolName: string;
    underlyingSymbol: string;
    positionSide: string;
    coverage: string;
    supplyUsd: number | null;
    debtUsd: number | null;
    netUsd: number | null;
  }>;
};

function groupByProtocol(
  positions: Array<{
    positionId: string;
    protocolKey: string;
    protocolName: string;
    underlyingSymbol: string;
    positionSide: string;
    coverage: string;
    supplyUsd: number | null;
    debtUsd: number | null;
    netUsd: number | null;
  }>,
): ProtocolGroup[] {
  const map = new Map<string, ProtocolGroup>();
  for (const pos of positions) {
    const existing = map.get(pos.protocolKey);
    const net = pos.netUsd ?? 0;
    if (existing) {
      existing.netUsd += net;
      existing.positions.push(pos);
    } else {
      map.set(pos.protocolKey, {
        protocolKey: pos.protocolKey,
        protocolName: pos.protocolName,
        netUsd: net,
        positions: [pos],
      });
    }
  }
  return [...map.values()];
}

function isDebtPosition(positionSide: string): boolean {
  return positionSide.toLowerCase() === "debt";
}

function isPartialCoverage(coverage: string): boolean {
  const normalized = coverage.toLowerCase();
  return normalized === "partial";
}

function displayPositionSide(positionSide: string): string {
  return positionSide.replace(/_/g, " ").toUpperCase();
}

function displayPositionType(
  position: {
    positionType?: string;
    positionSide: string;
  },
): string {
  const raw = position.positionType ?? position.positionSide;
  return raw.replace(/_/g, " ").toUpperCase();
}

function displayDetectionMode(mode: string): string {
  return mode.replace(/_/g, " ").toUpperCase();
}

function displayCoverage(coverage: string): string {
  const normalized = coverage.toLowerCase();
  if (normalized === "complete") return "FULL";
  return coverage.replace(/_/g, " ").toUpperCase();
}

function shareOf(valueUsd: number, totalUsd: number): number {
  if (!totalUsd || totalUsd <= 0) return 0;
  return (valueUsd / totalUsd) * 100;
}

function displayNetwork(network: string): string {
  if (network === "hyperliquid") return "HYPERLIQUID";
  return network.replace("-mainnet", "").replace(/-/g, " ").toUpperCase();
}

function shortAddress(value: string | null | undefined): string {
  if (!value) {
    return "";
  }
  return value.length <= 14 ? value : `${value.slice(0, 6)}...${value.slice(-4)}`;
}

function shortHash(value: string): string {
  return value.length <= 14 ? value : `${value.slice(0, 8)}...${value.slice(-6)}`;
}

function formatCurrency(value: number): string {
  return new Intl.NumberFormat("en-US", { style: "currency", currency: "USD", maximumFractionDigits: 2 }).format(value);
}

function formatQuantity(value: number): string {
  const abs = Math.abs(value);
  if (abs >= 1000) return value.toLocaleString("en-US", { maximumFractionDigits: 2 });
  if (abs >= 1) return value.toLocaleString("en-US", { maximumFractionDigits: 4 });
  return value.toLocaleString("en-US", { maximumFractionDigits: 8 });
}

function formatPercent(value: number | null): string {
  if (value === null || Number.isNaN(value)) return "N/A";
  return `${value > 0 ? "+" : ""}${value.toFixed(2)}%`;
}

function formatShare(value: number): string {
  return `${value.toFixed(2)}%`;
}

function formatShortDate(value: string): string {
  return new Date(`${value}T00:00:00`).toLocaleDateString("en-US", { month: "short", day: "numeric" });
}

function formatDateTime(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString("en-US", { month: "short", day: "2-digit", hour: "2-digit", minute: "2-digit" });
}

function formatHistoryPointTimestamp(localDate: string, isCurrent: boolean): string {
  const value = isCurrent ? new Date().toISOString() : `${localDate}T00:00:00Z`;
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return localDate;
  }
  return isCurrent
    ? `${date.toLocaleString("en-US", { month: "short", day: "2-digit", hour: "2-digit", minute: "2-digit" })} live`
    : `${date.toLocaleString("en-US", { month: "short", day: "2-digit", year: "numeric", hour: "2-digit", minute: "2-digit", timeZone: "UTC" })} UTC`;
}

function historyStartTimestamp(points: PortfolioHistoryPoint[]): number | null {
  const firstPoint = points
    .slice()
    .sort((left, right) => left.localDate.localeCompare(right.localDate))[0];
  if (!firstPoint) {
    return null;
  }
  const timestamp = new Date(`${firstPoint.localDate}T00:00:00Z`).getTime();
  return Number.isNaN(timestamp) ? null : Math.floor(timestamp / 1000);
}

function formatIndexAxis(value: number): string {
  return `${value.toFixed(0)}`;
}

function formatAxisCurrency(value: number): string {
  if (Math.abs(value) >= 1_000_000_000) {
    return `$${(value / 1_000_000_000).toFixed(1)}B`;
  }
  if (Math.abs(value) >= 1_000_000) {
    return `$${(value / 1_000_000).toFixed(1)}M`;
  }
  if (Math.abs(value) >= 1_000) {
    return `$${(value / 1_000).toFixed(1)}K`;
  }
  return `$${value.toFixed(0)}`;
}

function formatRelativeTime(value: string | null): string {
  if (!value) return "Never synced";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "Unknown";
  const diffMinutes = Math.floor((Date.now() - date.getTime()) / 60000);
  if (diffMinutes < 1) return "just now";
  if (diffMinutes < 60) return `${diffMinutes}m ago`;
  const diffHours = Math.floor(diffMinutes / 60);
  if (diffHours < 24) return `${diffHours}h ago`;
  return `${Math.floor(diffHours / 24)}d ago`;
}

function toneClass(value: number | null): string {
  if (value === null || value === 0) return "";
  return value > 0 ? "tone-positive" : "tone-negative";
}
