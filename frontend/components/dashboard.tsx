"use client";

import { FormEvent, useEffect, useMemo, useRef, useState } from "react";

import { DefiPositionsBlock } from "@/components/defi-positions-block";
import {
  computePeriodDelta,
  fetchChains,
  fetchPortfolioHistory,
  groupAllocationsByToken,
  isValidEvmAddress,
  normalizeAddress,
  PERIODS,
  Period,
  refreshPortfolio,
  walletSetHash,
} from "@/lib/portfolio";
import {
  getMeta,
  listWallets,
  removeWallet,
  saveWallet,
  setMeta,
} from "@/lib/storage";
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
  PortfolioSummaryResponse,
  WalletRecord,
} from "@/types/portfolio";

const SELECTED_CHAINS_KEY = "selected-chains";
const SWATCHES = ["#2f78d1", "#6d7fe7", "#5975db", "#f07a2c", "#bfdc3c", "#7ec8d8", "#12a7a1", "#a6a0dd"];
const OTHER_SWATCH = "#5d6674";
const MAX_ALLOCATION_SLICES = 6;

type AllocationMode = "token" | "chain" | "wallet";
type AllocationView = "strip" | "ring";

type ActivityItem = {
  id: string;
  kind: string;
  title: string;
  detail: string;
  timestamp: string;
  valueUsd: number | null;
};

type ChartPoint = {
  label: string;
  localDate: string;
  value: number;
  current: boolean;
  timestampLabel: string;
  tooltipContext: string;
};

type AllocationChartRow = ChainAllocation & {
  share: number;
  color: string;
  grouped: boolean;
  groupedCount: number;
};

export function Dashboard() {
  const [wallets, setWallets] = useState<WalletRecord[]>([]);
  const [chains, setChains] = useState<ChainOption[]>([]);
  const [selectedChains, setSelectedChains] = useState<string[]>([]);
  const [summary, setSummary] = useState<PortfolioSummaryResponse | null>(null);
  const [history, setHistory] = useState<PortfolioHistoryResponse | null>(null);
  const [assets, setAssets] = useState<AssetRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [historyWarning, setHistoryWarning] = useState<string | null>(null);
  const [inputAddress, setInputAddress] = useState("");
  const [inputLabel, setInputLabel] = useState("");
  const [duplicateAddress, setDuplicateAddress] = useState<string | null>(null);
  const [activeWallet, setActiveWallet] = useState<string | null>(null);
  const [period, setPeriod] = useState<Period>("24h");
  const [allocationMode, setAllocationMode] = useState<AllocationMode>("token");
  const [overviewAllocationView, setOverviewAllocationView] = useState<AllocationView>("strip");
  const [hoveredAllocationKey, setHoveredAllocationKey] = useState<string | null>(null);
  const [lastRefresh, setLastRefresh] = useState<string | null>(null);
  const [positions, setPositions] = useState<LendingPositionResponse[]>([]);
  const [defiPositions, setDefiPositions] = useState<DefiPositionResponse[]>([]);
  const [positionSummary, setPositionSummary] = useState<LendingPositionSummaryResponse | null>(null);
  const [defiSummary, setDefiSummary] = useState<DefiPositionSummaryResponse | null>(null);

  useEffect(() => {
    const bootstrap = async () => {
      try {
        const [storedWallets, supportedChains, savedSelectedChains, savedLastRefresh] =
          await Promise.all([
            listWallets(),
            fetchChains(),
            getMeta(SELECTED_CHAINS_KEY),
            getMeta("last-refresh"),
          ]);

        setWallets(storedWallets);
        setChains(supportedChains);
        setSelectedChains(
          savedSelectedChains?.split(",").filter(Boolean) ?? supportedChains.map((chain) => chain.id),
        );
        setLastRefresh(savedLastRefresh);
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
  const totalUsd = summary?.totalUsd ?? 0;
  const delta = useMemo(
    () => computePeriodDelta(history?.points ?? [], summary?.totalUsd ?? 0, period),
    [history?.points, summary?.totalUsd, period],
  );
  const chartPoints = useMemo(
    () => buildChartPoints(history?.points ?? [], summary?.totalUsd ?? null, period),
    [history?.points, summary?.totalUsd, period],
  );
  const walletAllocationRows = useMemo(
    () => buildWalletAllocationRows(summary?.walletAllocations ?? [], wallets),
    [summary?.walletAllocations, wallets],
  );
  const allocationRows = useMemo(
    () =>
      allocationMode === "token"
        ? groupAllocationsByToken(assets)
        : allocationMode === "wallet"
          ? walletAllocationRows
          : summary?.allocations ?? [],
    [allocationMode, assets, summary?.allocations, walletAllocationRows],
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
  const overviewLegendRows = useMemo(() => allocationChartRows.slice(0, 6), [allocationChartRows]);
  const allocationMeta = useMemo(() => allocationModeMeta(allocationMode), [allocationMode]);
  const activity = useMemo(
    () => buildActivity(wallets, history?.points ?? [], lastRefresh, summary?.totalUsd ?? null),
    [wallets, history?.points, lastRefresh, summary?.totalUsd],
  );
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
      setHistoryWarning(null);

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
    const normalized = normalizeAddress(inputAddress);

    if (!isValidEvmAddress(normalized)) {
      setError("Please enter a valid EVM address.");
      return;
    }

    const existingWallet = wallets.find((wallet) => wallet.normalizedAddress === normalized);
    if (existingWallet) {
      setDuplicateAddress(normalized);
      setActiveWallet(normalized);
      setError("Wallet already added. Opened the existing entry.");
      return;
    }

    const nextWallet: WalletRecord = {
      normalizedAddress: normalized,
      originalInput: inputAddress.trim(),
      label: inputLabel.trim(),
      createdAt: new Date().toISOString(),
    };

    await saveWallet(nextWallet);
    setWallets((current) => [...current, nextWallet]);
    setInputAddress("");
    setInputLabel("");
    setDuplicateAddress(null);
    setActiveWallet(normalized);
    setError(null);
  }

  async function handleRemoveWallet(address: string) {
    await removeWallet(address);
    setWallets((current) => current.filter((wallet) => wallet.normalizedAddress !== address));
    setActiveWallet(null);
    setSummary(null);
    setHistory(null);
    setAssets([]);
    setPositions([]);
    setDefiPositions([]);
    setPositionSummary(null);
    setDefiSummary(null);
  }

  async function toggleChain(chainId: string) {
    const nextSelection = selectedChains.includes(chainId)
      ? selectedChains.filter((item) => item !== chainId)
      : [...selectedChains, chainId];
    if (nextSelection.length === 0) {
      return;
    }
    setSelectedChains(nextSelection);
    await setMeta(SELECTED_CHAINS_KEY, nextSelection.join(","));
  }

  useEffect(() => {
    if (loading || wallets.length === 0 || selectedChains.length === 0 || summary === null) {
      return;
    }

    const loadHistory = async () => {
      try {
        const response = await fetchPortfolioHistory(wallets, selectedChains, period);
        setHistory(response);
        setHistoryWarning(
          response.partial
            ? `History is partial. Missing chains: ${response.missingChains.join(", ")}.`
            : null,
        );
      } catch (historyError) {
        setHistory(null);
        setHistoryWarning(historyError instanceof Error ? historyError.message : "Failed to load history");
      }
    };

    void loadHistory();
  }, [loading, period, selectedChains, summary, wallets]);

  if (loading) {
    return (
      <div className="s-layout">
        <header className="s-topbar">
          <span className="s-brand">P&amp;L_TERMINAL_V3</span>
        </header>
        <div className="s-loading">
          <p className="s-kicker">INITIALIZING_TERMINAL</p>
          <p className="s-loading-msg">Loading local vault, scope matrix, and portfolio state...</p>
        </div>
        <footer className="s-footer">
          <div className="s-footer-left"><span className="s-dot" />BOOTING</div>
        </footer>
      </div>
    );
  }

  const topNetwork = summary?.allocations[0]?.displayName ?? "—";
  const topWallet = walletAllocationRows[0]?.displayName ?? "—";
  const scopeLabel =
    chains.length === 0
      ? "Loading scope"
      : selectedChains.length === chains.length
        ? `All ${chains.length} EVM networks`
        : `${selectedChains.length}/${chains.length} networks`;

  return (
    <div className="s-layout">

      {/* ── TOP NAVBAR ─────────────────────────────────────────────── */}
      <header className="s-topbar">
        <div className="s-topbar-left">
          <span className="s-brand">P&amp;L_TERMINAL_V3</span>
          <nav className="s-topnav">
            <span className="s-topnav-active">LIVE_SYNC</span>
            <span className="s-topnav-link">{scopeLabel.toUpperCase().replace(/ /g, "_")}</span>
          </nav>
        </div>
        <div className="s-topbar-right">
          <span className="s-topbar-meta mono">{walletHash ? shortHash(walletHash) : "NO_HASH"}</span>
          <button
            className="s-btn-primary"
            type="button"
            onClick={() => void handleRefresh(true)}
            disabled={refreshing || wallets.length === 0}
          >
            {refreshing ? "SYNCING..." : "SYNC_LIVE"}
          </button>
          <span className="s-btn-outline s-btn-sm">AUTHORIZE_PRIVATE</span>
        </div>
      </header>

      {/* ── LEFT SIDEBAR ───────────────────────────────────────────── */}
      <aside className="s-sidebar">

        {/* User block */}
        <div className="s-sidebar-user">
          <div className="s-sidebar-avatar">P</div>
          <div>
            <p className="s-sidebar-name">OPERATOR_01</p>
            <p className="s-sidebar-level">LEVEL_3_AUTH</p>
          </div>
        </div>

        {/* Nav */}
        <nav className="s-sidenav">
          <a className="s-sidenav-item s-sidenav-active">DASHBOARD</a>
          <a className="s-sidenav-item">ANALYTICS</a>
          <a className="s-sidenav-item">NETWORK_EXPOSURE</a>
          <a className="s-sidenav-item">ASSET_INVENTORY</a>
          <a className="s-sidenav-item">RISK_ENGINE</a>
        </nav>

        {/* Wallet intake */}
        <div className="s-sidebar-section">
          <div className="s-sidebar-section-hd">WALLET_INTAKE</div>
          <form className="s-form" onSubmit={handleAddWallet}>
            <label className="s-field">
              <span className="s-field-label">ADDRESS</span>
              <input
                className="s-input"
                value={inputAddress}
                onChange={(e) => setInputAddress(e.target.value)}
                placeholder="0x..."
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
            <button className="s-btn-outline" type="submit">REGISTER_WALLET</button>
          </form>
          {error ? <p className="s-error">{error}</p> : null}
        </div>

        {/* Tracked wallets */}
        {wallets.length > 0 && (
          <div className="s-sidebar-section">
            <div className="s-sidebar-section-hd">
              <span>TRACKED_WALLETS</span>
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
            <span>NETWORK_SCOPE</span>
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
            {refreshing ? "SYNCING..." : "EXECUTE_SYNC"}
          </button>
          <div className="s-sidebar-sys">
            <span className="s-sys-line">PROVIDERS: ALCHEMY + GOLDRUSH</span>
            <span className="s-sys-line">STORAGE: SQLITE + INDEXEDDB</span>
            <span className="s-sys-line">LAST_SYNC: {formatRelativeTime(lastRefresh).toUpperCase()}</span>
          </div>
        </div>
      </aside>

      {/* ── MAIN CONTENT ───────────────────────────────────────────── */}
      <main className="s-main">

        {wallets.length === 0 ? (

          /* Empty state */
          <div className="s-empty">
            <p className="s-kicker">NO_ACTIVE_PORTFOLIO</p>
            <h2 className="s-empty-title">Register wallets to begin tracking.</h2>
            <div className="s-empty-steps">
              <div className="s-empty-step"><span>01</span><strong>WALLET_INTAKE</strong><p>Add addresses in the left command rail.</p></div>
              <div className="s-empty-step"><span>02</span><strong>SCOPE_MATRIX</strong><p>Limit the networks you want queried.</p></div>
              <div className="s-empty-step"><span>03</span><strong>EXECUTE_SYNC</strong><p>Refresh will hydrate and persist the first checkpoints.</p></div>
            </div>
          </div>

        ) : (
          <>
            {/* ── HERO: Total Net Worth ───────────────────────────────── */}
            <section className="s-hero">
              <p className="s-kicker">TOTAL_NET_WORTH</p>
              <div className="s-hero-row">
                <h1 className="s-hero-value">{formatCurrency(totalUsd)}</h1>
                {delta.amount !== null && (
                  <div className={`s-delta-badge ${toneClass(delta.amount)}`}>
                    <span className="s-delta-pct">{formatPercent(delta.percentage)}</span>
                    <span className="s-delta-amt mono">{formatCurrency(delta.amount)}</span>
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
                <span>{wallets.length}_TRACKED_WALLETS</span>
              </div>
              <div className="s-wallet-chips">
                {wallets.map((wallet, i) => (
                  <div key={wallet.normalizedAddress} className="s-wallet-chip">
                    <div className="s-wallet-chip-dot" style={{ background: SWATCHES[i % SWATCHES.length] }} />
                    <span className="mono">{wallet.label || shortAddress(wallet.originalInput)}</span>
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
                    <span>TECHNICAL_PERFORMANCE_SNAPSHOT</span>
                    <div className="s-panel-hd-controls">
                      <span className="s-live-badge">REALTIME_FEED</span>
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
                    <Chart points={chartPoints} />
                    {historyWarning ? <p className="s-warning">{historyWarning}</p> : null}
                  </div>
                </div>

                {/* Portfolio Command Summary */}
                <div className="s-panel">
                  <div className="s-panel-hd">
                    <span>PORTFOLIO_COMMAND_SUMMARY</span>
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
                    <button className={`s-tab ${allocationMode === "token" ? "is-active" : ""}`} type="button" onClick={() => setAllocationMode("token")}>BY_TOKEN</button>
                    <button className={`s-tab ${allocationMode === "chain" ? "is-active" : ""}`} type="button" onClick={() => setAllocationMode("chain")}>BY_NETWORK</button>
                    <button className={`s-tab ${allocationMode === "wallet" ? "is-active" : ""}`} type="button" onClick={() => setAllocationMode("wallet")}>BY_WALLET</button>
                  </div>
                  <div className="s-panel-body">
                    {overviewAllocationView === "ring" ? (
                      <div className="s-ring-layout">
                        <div>
                          <div className="s-mega">{formatCurrency(totalUsd)}</div>
                          <div className={`s-delta-line ${toneClass(delta.amount)}`}>
                            {delta.amount === null ? "Waiting for baseline" : `${formatCurrency(delta.amount)} / ${formatPercent(delta.percentage)}`}
                          </div>
                        </div>
                        <AllocationDonutChart
                          rows={allocationChartRows}
                          mode={allocationMode}
                          activeKey={hoveredAllocationKey}
                          onActiveKeyChange={setHoveredAllocationKey}
                        />
                      </div>
                    ) : (
                      <>
                        <div className="s-alloc-strip">
                          {allocationChartRows.slice(0, 8).map((row) => (
                            <span key={allocationRowKey(row)} style={{ width: `${row.share}%`, backgroundColor: row.color }} />
                          ))}
                        </div>
                        <div className="s-alloc-legend">
                          {overviewLegendRows.map((row) => (
                            <div key={allocationRowKey(row)} className="s-alloc-legend-row">
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

                <DefiPositionsBlock wallets={wallets} />

                {/* Asset Inventory */}
                <div className="s-panel">
                  <div className="s-panel-hd">
                    <span>ASSET_INVENTORY_TOP_{assets.length}</span>
                    <span className="s-badge">{assets.length} ROWS</span>
                  </div>
                  <div className="s-table-shell">
                    <table className="s-table">
                      <thead>
                        <tr>
                          <th>ASSET</th>
                          <th>VENUE</th>
                          <th>TYPE</th>
                          <th className="align-right">BALANCE</th>
                          <th className="align-right">PRICE</th>
                          <th className="align-right">VALUE</th>
                          <th className="align-right">WEIGHT</th>
                        </tr>
                      </thead>
                      <tbody>
                        {assets.map((asset) => (
                          <tr key={asset.assetId}>
                            <td data-label="Asset">
                              <div className="s-asset-cell">
                                <div className="s-asset-icon">{asset.symbol.slice(0, 1)}</div>
                                <div>
                                  <strong>{asset.symbol}</strong>
                                  <span>{asset.name || "Unknown"}</span>
                                </div>
                              </div>
                            </td>
                            <td data-label="Venue"><span className="s-tag">{displayNetwork(asset.network)}</span></td>
                            <td data-label="Type"><span className="s-tag">{asset.network === "hyperliquid" ? "VENUE" : asset.nativeToken ? "NATIVE" : "ERC-20"}</span></td>
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

              {/* Network Exposure Board */}
                <div className="s-panel">
                  <div className="s-panel-hd s-panel-hd-blue">
                    <span>NETWORK_EXPOSURE_BOARD</span>
                  </div>
                  <div className="s-panel-body s-exposure-list">
                    {(summary?.allocations ?? []).slice(0, 6).map((item, i) => (
                      <div key={item.network} className="s-exposure-row">
                        <div className="s-exposure-meta">
                          <span className="s-exposure-name">{item.displayName.toUpperCase()}</span>
                          <span className="mono s-exposure-pct">{formatShare(shareOf(item.valueUsd, totalUsd))}</span>
                        </div>
                        <div className="s-bar-track">
                          <div
                            className="s-bar-fill"
                            style={{
                              width: `${shareOf(item.valueUsd, totalUsd)}%`,
                              backgroundColor: SWATCHES[i % SWATCHES.length],
                            }}
                          />
                        </div>
                      </div>
                    ))}
                  </div>
                </div>

                {/* Allocation Detail */}
                <div className="s-panel">
                  <div className="s-panel-hd">
                    <span>ALLOCATION_DETAIL</span>
                    <span className="s-badge">{allocationMeta.label.toUpperCase()}</span>
                  </div>
                  <div className="s-panel-body">
                    <AllocationDetailList
                      rows={allocationChartRows}
                      mode={allocationMode}
                      totalRows={allocationRows.length}
                      activeKey={activeAllocationRow ? allocationRowKey(activeAllocationRow) : null}
                      onActiveKeyChange={setHoveredAllocationKey}
                    />
                  </div>
                </div>

                {/* System Readout */}
                <div className="s-panel">
                  <div className="s-panel-hd">
                    <span>SYSTEM_READOUT</span>
                    <span className="s-badge">SERVER</span>
                  </div>
                  <div className="s-panel-body s-readout-list">
                    <div className="s-readout-row">
                      <span className="s-readout-label">TOP_VENUE</span>
                      <strong className="s-readout-val">{topNetwork}</strong>
                    </div>
                    <div className="s-readout-row">
                      <span className="s-readout-label">TOP_WALLET</span>
                      <strong className="s-readout-val">{topWallet}</strong>
                    </div>
                    <div className="s-readout-row">
                      <span className="s-readout-label">TOP_ASSET</span>
                      <strong className="s-readout-val">{assets[0]?.symbol ?? "—"}</strong>
                    </div>
                    <div className="s-readout-row">
                      <span className="s-readout-label">HISTORY_PTS</span>
                      <strong className="s-readout-val mono">{history?.points.length ?? 0}</strong>
                    </div>
                    <div className="s-readout-row">
                      <span className="s-readout-label">NETWORKS_SCOPE</span>
                      <strong className="s-readout-val mono">{selectedChains.length}</strong>
                    </div>
                    <div className="s-readout-row">
                      <span className="s-readout-label">DELTA_PERIOD</span>
                      <strong className="s-readout-val mono">{delta.label}</strong>
                    </div>
                  </div>
                </div>

                {/* Ledger Activity */}
                <div className="s-panel">
                  <div className="s-panel-hd">
                    <span>LEDGER_ACTIVITY</span>
                    <span className="s-badge">{activity.length} EVENTS</span>
                  </div>
                  <div className="s-table-shell">
                    <table className="s-table s-table-compact">
                      <thead>
                        <tr>
                          <th>EVENT</th>
                          <th>DETAIL</th>
                          <th className="align-right">VALUE</th>
                        </tr>
                      </thead>
                      <tbody>
                        {activity.map((item) => (
                          <tr key={item.id}>
                            <td data-label="Event">
                              <div className="s-asset-cell">
                                <strong>{item.kind}</strong>
                                <span>{item.title}</span>
                              </div>
                            </td>
                            <td data-label="Detail" className="s-muted">{item.detail}</td>
                            <td data-label="Value" className={`align-right mono ${toneClass(item.valueUsd)}`}>
                              {item.valueUsd === null ? "N/A" : formatCurrency(item.valueUsd)}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
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
          <span>SYSTEM_STABLE</span>
          <span className="s-sep">|</span>
          <span>{wallets.length}_TRACKED_WALLETS</span>
          <span className="s-sep">|</span>
          <span>LAST_BLOCK: #{Math.floor(Date.now() / 1000).toLocaleString()}</span>
        </div>
        <div className="s-footer-right">
          <span>TERMINAL_SESSION: {walletHash ? shortHash(walletHash) : "NONE"}</span>
          <span className="s-sep">|</span>
          <span>P&amp;L_TERMINAL_V3</span>
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

function Chart({ points }: { points: ChartPoint[] }) {
  const viewportRef = useRef<HTMLDivElement>(null);
  const [hoveredPoint, setHoveredPoint] = useState<{
    point: ChartPoint;
    x: number;
    y: number;
    width: number;
    height: number;
  } | null>(null);

  if (points.length === 0) {
    return <div className="chart-empty"><strong>No history in scope</strong><p>Refresh to hydrate the first server-side baseline.</p></div>;
  }

  const width = 820;
  const height = 248;
  const padLeft = 84;
  const padRight = 18;
  const padTop = 14;
  const padBottom = 34;
  const values = points.map((point) => point.value);
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
  const coords = points.map((point, index) => {
    const x = points.length === 1 ? (padLeft + width - padRight) / 2 : padLeft + (index * (width - padLeft - padRight)) / Math.max(1, points.length - 1);
    const y = height - padBottom - ((point.value - floor) / range) * (height - padTop - padBottom);
    return { ...point, x, y };
  });
  const linePath = coords.map((point, index) => `${index === 0 ? "M" : "L"} ${point.x.toFixed(2)} ${point.y.toFixed(2)}`).join(" ");
  const areaPath = `${linePath} L ${coords[coords.length - 1].x.toFixed(2)} ${(height - padBottom).toFixed(2)} L ${coords[0].x.toFixed(2)} ${(height - padBottom).toFixed(2)} Z`;
  const step = Math.max(1, Math.ceil(points.length / 5));

  const handlePointHover = (
    event: React.MouseEvent<SVGCircleElement, MouseEvent>,
    point: ChartPoint,
  ) => {
    const rect = viewportRef.current?.getBoundingClientRect();
    if (!rect) {
      return;
    }
    setHoveredPoint({
      point,
      x: event.clientX - rect.left,
      y: event.clientY - rect.top,
      width: rect.width,
      height: rect.height,
    });
  };

  const hoveredCoord = hoveredPoint
    ? coords.find((point) => point.localDate === hoveredPoint.point.localDate && point.current === hoveredPoint.point.current) ?? null
    : null;

  return (
    <div className="chart-viewport" ref={viewportRef}>
      <svg viewBox={`0 0 ${width} ${height}`} className="chart-svg" aria-label="Portfolio chart">
        <rect x={padLeft} y={padTop} width={width - padLeft - padRight} height={height - padTop - padBottom} className="chart-frame" />
        <line x1={padLeft} y1={padTop} x2={padLeft} y2={height - padBottom} className="chart-axis-line" />
        <line x1={padLeft} y1={height - padBottom} x2={width - padRight} y2={height - padBottom} className="chart-axis-line" />
        {yTicks.map((tick) => {
          return (
            <g key={tick.key}>
              <line x1={padLeft} y1={tick.y} x2={width - padRight} y2={tick.y} className="chart-grid-line" />
              <text x={padLeft - 10} y={tick.y + 4} textAnchor="end" className="chart-axis-label">
                {formatAxisCurrency(tick.value)}
              </text>
            </g>
          );
        })}
        <path d={areaPath} className="chart-area" />
        <path d={linePath} className={`chart-line ${coords[coords.length - 1].value >= coords[0].value ? "is-up" : "is-down"}`} />
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
              onMouseEnter={(event) => handlePointHover(event, point)}
              onMouseMove={(event) => handlePointHover(event, point)}
              onMouseLeave={() => setHoveredPoint(null)}
            />
            {(index % step === 0 || index === coords.length - 1) && <text x={point.x} y={height - 8} textAnchor="middle" className="chart-label">{point.label}</text>}
          </g>
        ))}
      </svg>
      {hoveredPoint ? (
        <div
          className="chart-tooltip"
          style={{
            left: `${Math.max(Math.min(hoveredPoint.x + 18, hoveredPoint.width - 196), 12)}px`,
            top: `${Math.max(Math.min(hoveredPoint.y - 78, hoveredPoint.height - 96), 12)}px`,
          }}
        >
          <span>{hoveredPoint.point.tooltipContext}</span>
          <strong>{formatCurrency(hoveredPoint.point.value)}</strong>
          <p>{hoveredPoint.point.timestampLabel}</p>
        </div>
      ) : null}
    </div>
  );
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
        </div>
      </div>
    </div>
  );
}

function AllocationDetailList({
  rows,
  mode,
  totalRows,
  activeKey,
  onActiveKeyChange,
}: {
  rows: AllocationChartRow[];
  mode: AllocationMode;
  totalRows: number;
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
              <p className="allocation-legend-copy">
                {row.grouped
                  ? `${row.groupedCount} smaller ${modeMeta.plural} combined into a single terminal bucket.`
                  : `${modeMeta.label} concentration tracked inside the current ranked breakdown.`}
              </p>
            </div>
            <div className="allocation-legend-values">
              <strong>{formatShare(row.share)}</strong>
              <span className="mono">{formatCurrency(row.valueUsd)}</span>
              {index === 0 ? <span className="allocation-legend-note">Lead slice / {totalRows} total</span> : null}
            </div>
          </article>
        );
      })}
    </div>
  );
}

function buildChartPoints(historyPoints: PortfolioHistoryPoint[], currentTotalUsd: number | null, period: Period): ChartPoint[] {
  let points = historyPoints.map((point) => ({
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
      tooltipContext: "Live overview",
    };
    const existingIndex = points.findIndex((point) => point.localDate === currentDate);
    if (existingIndex >= 0) {
      points[existingIndex] = currentPoint;
    } else {
      points = [...points, currentPoint];
    }
  }

  if (period === "24h") return points.slice(-2);
  if (period === "7d") return points.slice(-8);
  if (period === "30d") return points.slice(-31);
  return points;
}

function buildAllocationChartRows(rows: ChainAllocation[], totalUsd: number): AllocationChartRow[] {
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

function buildWalletAllocationRows(
  walletAllocations: PortfolioSummaryResponse["walletAllocations"],
  wallets: WalletRecord[],
): ChainAllocation[] {
  const labelByAddress = new Map(
    wallets.map((wallet) => [
      wallet.normalizedAddress,
      wallet.label?.trim() ? `${wallet.label.trim()} / ${shortAddress(wallet.originalInput)}` : shortAddress(wallet.originalInput),
    ]),
  );

  return walletAllocations
    .map((allocation) => ({
      network: allocation.walletAddress,
      displayName: labelByAddress.get(allocation.walletAddress.toLowerCase()) ?? shortAddress(allocation.walletAddress),
      valueUsd: allocation.valueUsd,
    }))
    .sort((left, right) => right.valueUsd - left.valueUsd);
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

function buildActivity(
  wallets: WalletRecord[],
  historyPoints: PortfolioHistoryPoint[],
  lastRefresh: string | null,
  totalUsd: number | null,
): ActivityItem[] {
  const items: ActivityItem[] = [];

  if (lastRefresh) {
    items.push({
      id: `refresh-${lastRefresh}`,
      kind: "SYNC",
      title: "Live portfolio refresh completed",
      detail: "Composite portfolio overview fetched from providers",
      timestamp: lastRefresh,
      valueUsd: totalUsd,
    });
  }

  for (const wallet of wallets) {
    items.push({
      id: `wallet-${wallet.normalizedAddress}`,
      kind: "WALLET",
      title: "Wallet registered into local watch set",
      detail: wallet.label || shortAddress(wallet.originalInput),
      timestamp: wallet.createdAt,
      valueUsd: null,
    });
  }

  for (const point of historyPoints.slice(-8)) {
    items.push({
      id: `snapshot-${point.localDate}`,
      kind: "HISTORY",
      title: "Server checkpoint available",
      detail: `${point.localDate} / ${point.source}`,
      timestamp: point.localDate,
      valueUsd: point.totalUsd,
    });
  }

  return items.sort((left, right) => right.timestamp.localeCompare(left.timestamp)).slice(0, 10);
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

function shortAddress(value: string): string {
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
