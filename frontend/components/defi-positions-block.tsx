"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";

import type { WalletRecord } from "@/types/portfolio";

const API_BASE_URL =
  process.env.NEXT_PUBLIC_API_BASE_URL?.replace(/\/$/, "") ?? "http://localhost:8080/api";

type DefiPositionsBlockProps = {
  wallets: WalletRecord[];
};

type RawDefiResponse = {
  totalUsd?: number | null;
  totalValueUsd?: number | null;
  stale?: boolean;
  positions?: RawDefiPosition[];
};

type RawDefiPosition = {
  positionType?: string | null;
  protocol?: string | null;
  protocolName?: string | null;
  protocolModule?: string | null;
  protocolUrl?: string | null;
  poolAddress?: string | null;
  groupId?: string | null;
  chain?: string | null;
  chainId?: string | null;
  tokenSymbol?: string | null;
  tokenName?: string | null;
  tokenIconUrl?: string | null;
  tokenAmount?: number | null;
  quantity?: number | null;
  valueUsd?: number | null;
  value?: number | null;
  change24hUsd?: number | null;
  absoluteChange1d?: number | null;
  change24hPercent?: number | null;
  percentChange1d?: number | null;
};

type WorkerPositionToken = {
  mint: string;
  symbol: string;
  decimals: number;
  amount: number;
  priceUsd: number;
  valueUsd: number;
};

type WorkerPosition = {
  protocolId: string;
  protocolName: string;
  category: string;
  positionType: string;
  positionId: string;
  tokens: WorkerPositionToken[];
  totalValueUsd: number;
  pendingRewards?: WorkerPositionToken[];
  metadata?: Record<string, unknown>;
};

type WorkerPositionsResponse = {
  walletAddress: string;
  fetchedAt: string;
  positions: WorkerPosition[];
  errors: Array<{ protocolId: string; message: string }>;
};

type DefiPosition = {
  key: string;
  walletAddress: string;
  walletLabel: string;
  positionType: string;
  protocol: string;
  protocolName: string;
  protocolModule: string | null;
  protocolUrl: string | null;
  poolAddress: string | null;
  groupId: string | null;
  chain: string;
  tokenSymbol: string;
  tokenName: string;
  tokenIconUrl: string | null;
  tokenAmount: number | null;
  valueUsd: number | null;
  change24hUsd: number | null;
  change24hPercent: number | null;
};

type DefiGroup = {
  key: string;
  protocolName: string;
  protocolUrl: string | null;
  chain: string;
  walletLabel: string;
  protocolModule: string | null;
  primaryPositions: DefiPosition[];
  rewardPositions: DefiPosition[];
  totalValueUsd: number;
  change24hUsd: number | null;
  sharedPositionType: string | null;
};

type DefiState = {
  totalValueUsd: number;
  positions: DefiPosition[];
  stale: boolean;
  sourceFailures: string[];
};

export function DefiPositionsBlock({ wallets }: DefiPositionsBlockProps) {
  const [state, setState] = useState<DefiState>({
    totalValueUsd: 0,
    positions: [],
    stale: false,
    sourceFailures: [],
  });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [expanded, setExpanded] = useState(false);
  const [gridWidth, setGridWidth] = useState(0);
  const groupsRef = useRef<HTMLDivElement | null>(null);
  const activeRequestRef = useRef<AbortController | null>(null);

  const loadPositions = useCallback(async () => {
    activeRequestRef.current?.abort();

    if (wallets.length === 0) {
      setState({
        totalValueUsd: 0,
        positions: [],
        stale: false,
        sourceFailures: [],
      });
      setLoading(false);
      setError(null);
      return;
    }

    const controller = new AbortController();
    activeRequestRef.current = controller;

    setLoading(true);
    setError(null);

    try {
      const results = await Promise.allSettled(
        wallets.map(async (wallet) => {
          const [evmResult, solanaResult] = await Promise.allSettled([
            fetchEvmPositions(wallet, controller.signal),
            fetchSolanaWorkerPositions(wallet, controller.signal),
          ]);

          const positions: RawDefiPosition[] = [
            ...(evmResult.status === "fulfilled" ? evmResult.value : []),
            ...(solanaResult.status === "fulfilled" ? solanaResult.value : []),
          ];

          const evmTotal = evmResult.status === "fulfilled"
            ? evmResult.value.reduce((sum, position) => sum + (position.valueUsd ?? position.value ?? 0), 0)
            : 0;

          const solanaTotal = solanaResult.status === "fulfilled"
            ? solanaResult.value.reduce((sum, position) => sum + (position.valueUsd ?? 0), 0)
            : 0;

          const failures: string[] = [
            ...(evmResult.status === "rejected" ? ["EVM"] : []),
            ...(solanaResult.status === "rejected" ? ["Solana"] : []),
          ];

          return { wallet, positions, totalValueUsd: evmTotal + solanaTotal, failures };
        }),
      );

      if (controller.signal.aborted) {
        return;
      }

      const fulfilledResults = results.flatMap((result) =>
        result.status === "fulfilled" ? [result.value] : [],
      );
      const positions = fulfilledResults.flatMap(({ wallet, positions: walletPositions }) =>
        walletPositions.map((position, index) => normalizeDefiPosition(position, wallet, index)),
      );
      const totalValueUsd = fulfilledResults.reduce(
        (sum, result) => sum + result.totalValueUsd,
        0,
      );
      const sourceFailures = uniqueSourceFailures(
        fulfilledResults.flatMap((result) => result.failures),
      );

      setState({
        totalValueUsd,
        positions,
        stale: false,
        sourceFailures,
      });
    } catch (loadError) {
      if (controller.signal.aborted) {
        return;
      }
      setError(loadError instanceof Error ? loadError.message : "Failed to load DeFi positions.");
    } finally {
      if (!controller.signal.aborted) {
        setLoading(false);
      }
      if (activeRequestRef.current === controller) {
        activeRequestRef.current = null;
      }
    }
  }, [wallets]);

  useEffect(() => {
    void loadPositions();

    return () => {
      activeRequestRef.current?.abort();
    };
  }, [loadPositions]);

  const groupedPositions = useMemo(() => buildDefiGroups(state.positions), [state.positions]);
  const columns = useMemo(() => inferColumns(gridWidth), [gridWidth]);
  const collapsedCount = columns * 2;
  const hasOverflow = groupedPositions.length > collapsedCount;
  const visibleGroups = expanded || !hasOverflow
    ? groupedPositions
    : groupedPositions.slice(0, collapsedCount);

  useEffect(() => {
    setExpanded(false);
  }, [wallets.length, state.positions.length]);

  useEffect(() => {
    const node = groupsRef.current;
    if (!node) {
      return;
    }

    const updateWidth = () => {
      setGridWidth(node.getBoundingClientRect().width);
    };

    updateWidth();

    const observer = new ResizeObserver(() => {
      updateWidth();
    });

    observer.observe(node);

    return () => {
      observer.disconnect();
    };
  }, [visibleGroups.length, hasOverflow, expanded]);

  return (
    <section className="s-panel">
      <div className="s-panel-hd s-panel-hd-violet">
        <span>DEFI_POSITIONS_BLOCK</span>
        <div className="s-panel-hd-controls">
          <span className="s-badge">{groupedPositions.length} GROUPS</span>
          <span className="s-badge">{state.positions.length} POSITIONS</span>
          {state.stale ? <span className="s-badge">STALE</span> : null}
        </div>
      </div>

      <div className="s-panel-body s-defi-block">
        <div className="s-defi-summary-bar">
          <div className="s-defi-summary-main">
            <span className="s-defi-summary-label">Total DeFi Value</span>
            <strong className="s-defi-summary-value mono">{formatCurrency(state.totalValueUsd)}</strong>
            <p className="s-defi-summary-copy">
              Grouped by protocol pool and reward leg across the tracked wallet set.
            </p>
          </div>
          <div className="s-defi-summary-meta">
            <span className="s-badge">{wallets.length} WALLETS</span>
            {state.stale ? <span className="s-badge">CACHED_SOURCE</span> : <span className="s-live-badge">LIVE_SYNC</span>}
          </div>
        </div>

        {state.sourceFailures.length > 0 ? (
          <div className="s-defi-source-warning">
            <span>Some chains failed to load: {state.sourceFailures.join(", ")}.</span>
            <button type="button" className="s-btn-outline" onClick={() => void loadPositions()}>
              Retry
            </button>
          </div>
        ) : null}

        {wallets.length === 0 ? (
          <StatePanel
            title="Track a wallet to load DeFi positions."
            copy="This panel reads the Zerion-backed endpoint per wallet and assembles the grouped protocol view once at least one address is registered."
          />
        ) : loading ? (
          <LoadingSkeleton />
        ) : error ? (
          <StatePanel
            tone="error"
            title="DeFi positions failed to load."
            copy={error}
          />
        ) : groupedPositions.length === 0 ? (
          <StatePanel
            title="No DeFi positions found."
            copy="The tracked wallets did not return active protocol positions from the backend for the current sync scope."
          />
        ) : (
          <>
            <div className="s-defi-group-grid" ref={groupsRef}>
              {visibleGroups.map((group) => (
              <article key={group.key} className="s-defi-group-card">
                <header className="s-defi-group-header">
                  <div className="s-defi-group-identity">
                    <ProtocolIcon protocolName={group.protocolName} protocolUrl={group.protocolUrl} />
                    <div className="s-defi-group-copy">
                      <strong>{group.protocolName}</strong>
                      {group.protocolModule ? <p className="s-defi-group-module">{toTitleCase(group.protocolModule)}</p> : null}
                      <div className="s-defi-group-meta">
                        <span className={`s-defi-chain-badge ${chainToneClass(group.chain)}`}>
                          {displayChain(group.chain)}
                        </span>
                        {wallets.length > 1 ? <span className="s-badge">{group.walletLabel}</span> : null}
                      </div>
                    </div>
                  </div>
                  <div className="s-defi-group-metrics">
                    <div className="s-defi-metric-stack">
                      <span>Group Value</span>
                      <strong className="mono">{formatCurrency(group.totalValueUsd)}</strong>
                    </div>
                    <div className="s-defi-metric-stack">
                      <span>24H Delta</span>
                      {group.change24hUsd !== null ? (
                        <strong className={`mono ${changeToneClass(group.change24hUsd)}`}>
                          {formatSignedCurrency(group.change24hUsd)}
                        </strong>
                      ) : (
                        <strong className="s-muted">Not reported</strong>
                      )}
                    </div>
                  </div>
                </header>

                <div className="s-defi-position-list s-defi-position-shell">
                  {(group.primaryPositions.length > 0 ? group.primaryPositions : group.rewardPositions).map((position, index) => (
                    <PositionRow
                      key={position.key}
                      position={position}
                      nested={false}
                      hideTypeBadge={group.sharedPositionType !== null && index > 0}
                      compact={group.primaryPositions.length > 1}
                    />
                  ))}
                </div>

                {group.primaryPositions.length > 0 && group.rewardPositions.length > 0 ? (
                  <div className="s-defi-reward-stack">
                    <div className="s-defi-position-list s-defi-position-list-nested">
                      {group.rewardPositions.map((position) => (
                        <PositionRow
                          key={position.key}
                          position={position}
                          nested
                          hideTypeBadge={false}
                          compact
                        />
                      ))}
                    </div>
                  </div>
                ) : null}
              </article>
              ))}
            </div>
            {hasOverflow ? (
              <div className="s-defi-actions">
                <button
                  className="s-btn-outline s-defi-show-more"
                  type="button"
                  onClick={() => setExpanded((current) => !current)}
                >
                  {expanded ? "SHOW_LESS" : `SHOW_MORE_${groupedPositions.length - visibleGroups.length}`}
                </button>
              </div>
            ) : null}
          </>
        )}
      </div>
    </section>
  );
}

function PositionRow({
  position,
  nested,
  hideTypeBadge,
  compact,
}: {
  position: DefiPosition;
  nested: boolean;
  hideTypeBadge: boolean;
  compact: boolean;
}) {
  return (
    <div className={`s-defi-position-row ${nested ? "is-nested" : ""} ${compact ? "is-compact" : ""}`}>
      <div className="s-defi-position-main">
        {!hideTypeBadge ? (
          <div className="s-defi-position-topline">
            <span className={`s-defi-type-badge ${positionToneClass(position.positionType)}`}>
              {displayPositionType(position.positionType)}
            </span>
          </div>
        ) : null}
        <div className="s-defi-position-asset">
          <div className="s-defi-token-identity">
            <TokenIcon tokenName={position.tokenName} tokenSymbol={position.tokenSymbol} tokenIconUrl={position.tokenIconUrl} />
            <div className="s-defi-token-copy">
              <strong>{position.tokenName}</strong>
              <span>{position.tokenSymbol}</span>
            </div>
          </div>
        </div>
      </div>
      <div className="s-defi-position-values">
        <div className="s-defi-position-stat">
          <span>Amount</span>
          <strong className="mono">{formatAmountWithSymbol(position)}</strong>
        </div>
        <div className="s-defi-position-stat">
          <span>Value</span>
          <strong className="mono">{formatCurrency(position.valueUsd ?? 0)}</strong>
        </div>
      </div>
    </div>
  );
}

function ProtocolIcon({
  protocolName,
  protocolUrl,
}: {
  protocolName: string;
  protocolUrl: string | null;
}) {
  const [failed, setFailed] = useState(false);

  const faviconUrl =
    protocolUrl && !failed
      ? `https://www.google.com/s2/favicons?sz=64&domain_url=${encodeURIComponent(protocolUrl)}`
      : null;

  return (
    <div className="s-defi-protocol-icon" aria-hidden="true">
      {faviconUrl ? (
        <img
          src={faviconUrl}
          alt=""
          className="s-defi-protocol-icon-img"
          onError={() => setFailed(true)}
          loading="lazy"
          decoding="async"
        />
      ) : (
        <span>{protocolName.slice(0, 1).toUpperCase()}</span>
      )}
    </div>
  );
}

function TokenIcon({
  tokenName,
  tokenSymbol,
  tokenIconUrl,
}: {
  tokenName: string;
  tokenSymbol: string;
  tokenIconUrl: string | null;
}) {
  const [failed, setFailed] = useState(false);

  if (!tokenIconUrl || failed) {
    return (
      <span className="s-defi-token-icon-fallback" aria-hidden="true">
        {(tokenSymbol || tokenName).slice(0, 1).toUpperCase()}
      </span>
    );
  }

  return (
    <img
      src={tokenIconUrl}
      alt=""
      className="s-defi-token-icon"
      loading="lazy"
      decoding="async"
      onError={() => setFailed(true)}
    />
  );
}

function StatePanel({
  title,
  copy,
  tone = "default",
}: {
  title: string;
  copy: string;
  tone?: "default" | "error";
}) {
  return (
    <div className={`s-defi-state ${tone === "error" ? "is-error" : ""}`}>
      <strong>{title}</strong>
      <p>{copy}</p>
    </div>
  );
}

function LoadingSkeleton() {
  return (
    <div className="s-defi-group-grid" aria-hidden="true">
      {Array.from({ length: 3 }).map((_, index) => (
        <div key={index} className="s-defi-group-card is-skeleton">
          <div className="s-defi-skeleton-line s-defi-skeleton-line-lg" />
          <div className="s-defi-skeleton-meta">
            <div className="s-defi-skeleton-line s-defi-skeleton-pill" />
            <div className="s-defi-skeleton-line s-defi-skeleton-pill" />
          </div>
          <div className="s-defi-skeleton-line s-defi-skeleton-line-md" />
          <div className="s-defi-skeleton-line" />
          <div className="s-defi-skeleton-line" />
        </div>
      ))}
    </div>
  );
}

async function fetchEvmPositions(wallet: WalletRecord, signal: AbortSignal): Promise<RawDefiPosition[]> {
  const response = await fetch(
    `${API_BASE_URL}/v1/wallets/${wallet.normalizedAddress}/defi-positions`,
    {
      cache: "no-store",
      signal,
    },
  );

  if (!response.ok) {
    throw new Error(`Failed to load EVM DeFi positions for ${wallet.label || shortAddress(wallet.originalInput)}.`);
  }

  const payload = (await response.json()) as RawDefiResponse;
  return payload.positions ?? [];
}

async function fetchSolanaWorkerPositions(wallet: WalletRecord, signal: AbortSignal): Promise<RawDefiPosition[]> {
  const response = await fetch(
    `${API_BASE_URL}/v1/wallets/${wallet.normalizedAddress}/solana/defi-positions`,
    {
      cache: "no-store",
      signal,
    },
  );

  if (!response.ok) {
    throw new Error(`Failed to load Solana DeFi positions for ${wallet.label || shortAddress(wallet.originalInput)}.`);
  }

  const payload = (await response.json()) as WorkerPositionsResponse;
  const workerUnavailable = payload.errors?.some((error) => error.protocolId === "worker") ?? false;

  if (workerUnavailable) {
    throw new Error("Solana worker failed to load positions.");
  }

  return (payload.positions ?? []).flatMap((position) => [
    ...(position.tokens ?? []).map((token) => mapWorkerPositionToRaw(position, token)),
    ...(position.pendingRewards ?? []).map((token) => mapWorkerPositionToRaw(position, token, "reward")),
  ]);
}

function mapWorkerPositionToRaw(
  workerPosition: WorkerPosition,
  token: WorkerPositionToken,
  positionType = workerPosition.positionType,
): RawDefiPosition {
  return {
    positionType,
    protocol: workerPosition.protocolId,
    protocolName: workerPosition.protocolName,
    chain: "solana",
    chainId: "solana",
    tokenSymbol: token.symbol,
    tokenName: token.symbol,
    tokenIconUrl: null,
    tokenAmount: token.amount,
    quantity: token.amount,
    valueUsd: token.valueUsd,
    value: token.valueUsd,
    groupId: workerPosition.positionId,
    poolAddress: null,
    protocolUrl: null,
    protocolModule: workerPosition.category ?? null,
    change24hUsd: null,
    absoluteChange1d: null,
    change24hPercent: null,
    percentChange1d: null,
  };
}

function uniqueSourceFailures(failures: string[]): string[] {
  return [...new Set(failures)];
}

function buildDefiGroups(positions: DefiPosition[]): DefiGroup[] {
  const groups = new Map<string, DefiGroup>();

  for (const position of positions) {
    const poolGroupingKey = position.poolAddress
      ? `${position.protocol}::${position.poolAddress}`
      : position.groupId ?? position.protocol;
    const fallbackKey = [position.walletAddress, poolGroupingKey].join("::");
    const key = fallbackKey.toLowerCase();
    const existing = groups.get(key);
    const valueUsd = position.valueUsd ?? 0;

    if (!existing) {
      groups.set(key, {
        key,
        protocolName: position.protocolName,
        protocolUrl: position.protocolUrl,
        chain: position.chain,
        walletLabel: position.walletLabel,
        protocolModule: position.protocolModule,
        primaryPositions: isRewardPosition(position.positionType) ? [] : [position],
        rewardPositions: isRewardPosition(position.positionType) ? [position] : [],
        totalValueUsd: valueUsd,
        change24hUsd: position.change24hUsd,
        sharedPositionType: isRewardPosition(position.positionType) ? null : position.positionType,
      });
      continue;
    }

    existing.totalValueUsd += valueUsd;
    existing.change24hUsd = sumNullable(existing.change24hUsd, position.change24hUsd);
    if (!existing.protocolModule && position.protocolModule) {
      existing.protocolModule = position.protocolModule;
    }
    existing.chain = resolveGroupChain(existing.chain, position.chain, existing.primaryPositions, existing.rewardPositions);
    if (!isRewardPosition(position.positionType) && existing.sharedPositionType !== position.positionType) {
      existing.sharedPositionType = null;
    }

    if (isRewardPosition(position.positionType)) {
      existing.rewardPositions.push(position);
    } else {
      existing.primaryPositions.push(position);
    }
  }

  return [...groups.values()]
    .map((group) => {
      const primaryPositions = group.primaryPositions.sort(sortPositions);
      const rewardPositions = group.rewardPositions.sort(sortPositions);

      return {
        ...group,
        primaryPositions,
        rewardPositions,
        chain: resolveGroupChain(group.chain, group.chain, primaryPositions, rewardPositions),
        sharedPositionType: resolveSharedPositionType(primaryPositions),
      };
    })
    .sort((left, right) => right.totalValueUsd - left.totalValueUsd);
}

function normalizeDefiPosition(
  position: RawDefiPosition,
  wallet: WalletRecord,
  index: number,
): DefiPosition {
  const tokenSymbol = position.tokenSymbol?.trim() || "UNKNOWN";
  const protocolName = position.protocolName?.trim() || position.protocol?.trim() || "Unknown protocol";
  const protocol = position.protocol?.trim() || protocolName;
  const protocolModule = position.protocolModule?.trim() || null;
  const chain = normalizeChain(position.chain?.trim() || position.chainId?.trim() || "unknown");
  const tokenName = position.tokenName?.trim() || tokenSymbol;
  const tokenAmount = position.tokenAmount ?? position.quantity ?? null;
  const valueUsd = position.valueUsd ?? position.value ?? null;
  const change24hUsd = position.change24hUsd ?? position.absoluteChange1d ?? null;
  const change24hPercent = position.change24hPercent ?? position.percentChange1d ?? null;
  const walletLabel = wallet.label?.trim() || shortAddress(wallet.originalInput);
  const keyParts = [
    wallet.normalizedAddress,
    position.groupId ?? position.poolAddress ?? protocol,
    tokenSymbol,
    position.positionType ?? "position",
    index,
  ];

  return {
    key: keyParts.join("::").toLowerCase(),
    walletAddress: wallet.normalizedAddress,
    walletLabel,
    positionType: position.positionType?.trim() || "position",
    protocol,
    protocolName,
    protocolModule,
    protocolUrl: position.protocolUrl?.trim() || null,
    poolAddress: position.poolAddress?.trim() || null,
    groupId: position.groupId?.trim() || null,
    chain,
    tokenSymbol,
    tokenName,
    tokenIconUrl: position.tokenIconUrl?.trim() || null,
    tokenAmount,
    valueUsd,
    change24hUsd,
    change24hPercent,
  };
}

function sortPositions(left: DefiPosition, right: DefiPosition): number {
  return (right.valueUsd ?? 0) - (left.valueUsd ?? 0);
}

function inferColumns(width: number): number {
  if (width <= 0) {
    return 3;
  }

  if (width < 720) {
    return 1;
  }

  if (width < 1120) {
    return 2;
  }

  return 3;
}

function sumNullable(left: number | null, right: number | null): number | null {
  if (left === null && right === null) {
    return null;
  }

  return (left ?? 0) + (right ?? 0);
}

function isRewardPosition(positionType: string): boolean {
  return positionType.trim().toLowerCase() === "reward";
}

function displayPositionType(positionType: string): string {
  return toTitleCase(positionType);
}

function displayChain(chain: string): string {
  const normalized = normalizeChain(chain);

  if (normalized === "binance-smart-chain") return "BSC";
  if (normalized === "ethereum") return "Ethereum";
  if (normalized === "arbitrum") return "Arbitrum";
  if (normalized === "polygon") return "Polygon";
  if (normalized === "optimism") return "Optimism";
  if (normalized === "base") return "Base";
  if (normalized === "solana") return "Solana";

  return toTitleCase(normalized.replace(/-/g, " "));
}

function chainToneClass(chain: string): string {
  const normalized = normalizeChain(chain);

  if (normalized === "ethereum") return "is-ethereum";
  if (normalized === "arbitrum") return "is-arbitrum";
  if (normalized === "polygon") return "is-polygon";
  if (normalized === "optimism") return "is-optimism";
  if (normalized === "base") return "is-base";
  if (normalized === "solana") return "is-solana";
  if (normalized === "binance-smart-chain") return "is-bsc";

  return "is-default";
}

function normalizeChain(value: string): string {
  const normalized = value.trim().toLowerCase();

  if (normalized === "0g") {
    return "ethereum";
  }

  return normalized;
}

function resolveGroupChain(
  currentChain: string,
  incomingChain: string,
  primaryPositions: DefiPosition[],
  rewardPositions: DefiPosition[],
): string {
  const orderedChains = [currentChain, incomingChain, ...primaryPositions.map((position) => position.chain), ...rewardPositions.map((position) => position.chain)]
    .map((chain) => normalizeChain(chain))
    .filter(Boolean);

  if (orderedChains.includes("ethereum")) return "ethereum";
  if (orderedChains.includes("arbitrum")) return "arbitrum";
  if (orderedChains.includes("base")) return "base";
  if (orderedChains.includes("solana")) return "solana";
  if (orderedChains.includes("binance-smart-chain")) return "binance-smart-chain";
  if (orderedChains.includes("optimism")) return "optimism";
  if (orderedChains.includes("polygon")) return "polygon";

  return orderedChains[0] ?? "unknown";
}

function resolveSharedPositionType(primaryPositions: DefiPosition[]): string | null {
  if (primaryPositions.length <= 1) {
    return null;
  }

  const firstType = primaryPositions[0]?.positionType ?? null;
  if (!firstType) {
    return null;
  }

  return primaryPositions.every((position) => position.positionType === firstType) ? firstType : null;
}

function positionToneClass(positionType: string): string {
  const normalized = positionType.trim().toLowerCase();

  if (normalized === "deposit") return "is-deposit";
  if (normalized === "loan") return "is-loan";
  if (normalized === "staked") return "is-staked";
  if (normalized === "reward") return "is-reward";
  if (normalized === "locked") return "is-locked";
  if (normalized === "investment") return "is-investment";

  return "is-default";
}

function changeToneClass(value: number): string {
  if (value > 0) return "tone-positive";
  if (value < 0) return "tone-negative";
  return "";
}

function toTitleCase(value: string): string {
  return value
    .replace(/[_-]+/g, " ")
    .replace(/\b\w/g, (match) => match.toUpperCase());
}

function shortAddress(value: string): string {
  return value.length <= 14 ? value : `${value.slice(0, 6)}...${value.slice(-4)}`;
}

function formatCurrency(value: number): string {
  return new Intl.NumberFormat("en-US", {
    style: "currency",
    currency: "USD",
    maximumFractionDigits: 2,
  }).format(value);
}

function formatSignedCurrency(value: number): string {
  return `${value > 0 ? "+" : value < 0 ? "-" : ""}${formatCurrency(Math.abs(value))}`;
}

function formatSignedPercent(value: number): string {
  return `${value > 0 ? "+" : value < 0 ? "-" : ""}${Math.abs(value).toFixed(2)}%`;
}

function formatAmountWithSymbol(position: DefiPosition): string {
  if (position.tokenAmount === null) {
    return "Unavailable";
  }

  return `${formatTokenAmount(position.tokenAmount)} ${position.tokenSymbol}`;
}

function formatTokenAmount(value: number): string {
  const abs = Math.abs(value);

  if (abs >= 1000) {
    return value.toLocaleString("en-US", { maximumFractionDigits: 2 });
  }

  if (abs >= 1) {
    return value.toLocaleString("en-US", { maximumFractionDigits: 4 });
  }

  return value.toLocaleString("en-US", { maximumFractionDigits: 8 });
}
