import { PublicKey } from "@solana/web3.js";
import { createRequire } from "module";
import type { DeFiAdapter, Position } from "../types.js";
import { connection, withRpcRetry } from "../rpc.js";
import { logger } from "../logger.js";
import { makePosition, pricedToken, readNumber, readString, runAdapterSafely, toHumanAmount } from "./utils.js";

const require = createRequire(import.meta.url);
const METEORA_DLMM_DATA_API_BASE = "https://dlmm.datapi.meteora.ag";
const METEORA_DLMM_API_BASE = "https://dlmm-api.meteora.ag";
const metadataCache = new Map<string, CacheEntry<MeteoraPoolMetadata | undefined>>();
const earningsCache = new Map<string, CacheEntry<MeteoraWalletEarning[] | undefined>>();
const METEORA_REST_CACHE_MS = 60_000;

export class MeteoraDlmmAdapter implements DeFiAdapter {
  protocolId = "meteora-dlmm" as const;
  protocolName = "Meteora DLMM" as const;
  category = "clmm" as const;

  async fetchPositions(walletAddress: string): Promise<Position[]> {
    return runAdapterSafely(this, walletAddress, async () => {
      const dlmmModule = require("@meteora-ag/dlmm") as Record<string, unknown>;
      const DLMM = dlmmModule.default ?? dlmmModule.DLMM ?? dlmmModule;
      if (!DLMM || typeof (DLMM as { getAllLbPairPositionsByUser?: unknown }).getAllLbPairPositionsByUser !== "function") {
        throw new Error("Meteora DLMM SDK does not expose getAllLbPairPositionsByUser");
      }
      const user = new PublicKey(walletAddress);
      const knownPools = getConfiguredPools();
      const positionsByPair =
        knownPools.length > 0
          ? await this.fetchConfiguredPoolPositions(DLMM as MeteoraDlmmSdk, user, knownPools)
          : ((await withRpcRetry(() =>
              (DLMM as MeteoraDlmmSdk).getAllLbPairPositionsByUser(connection, user, undefined, {
                chunkSize: 20,
                isParallelExecution: false
              })
            )) as Map<string, unknown>);

      return this.normalizePositions(walletAddress, positionsByPair);
    });
  }

  private async fetchConfiguredPoolPositions(
    DLMM: MeteoraDlmmSdk,
    user: PublicKey,
    poolAddresses: string[]
  ): Promise<Map<string, unknown>> {
    const positionsByPair = new Map<string, unknown>();

    for (const poolAddress of poolAddresses) {
      const pool = (await withRpcRetry(() => DLMM.create(connection, new PublicKey(poolAddress)))) as {
        getPositionsByUserAndLbPair: (user: PublicKey, options?: unknown) => Promise<Record<string, unknown>>;
        lbPair?: unknown;
        tokenX?: unknown;
        tokenY?: unknown;
      };
      const result = await withRpcRetry(() =>
        pool.getPositionsByUserAndLbPair(user, {
          chunkSize: 20,
          isParallelExecution: false
        })
      );
      const userPositions = (result.userPositions ?? []) as unknown[];

      if (userPositions.length > 0) {
        positionsByPair.set(poolAddress, {
          publicKey: new PublicKey(poolAddress),
          lbPair: pool.lbPair,
          tokenX: pool.tokenX,
          tokenY: pool.tokenY,
          lbPairPositionsData: userPositions
        });
      }
    }

    return positionsByPair;
  }

  private async normalizePositions(walletAddress: string, positionsByPair: Map<string, unknown>): Promise<Position[]> {
    const positions: Position[] = [];

    for (const [poolAddress, pairInfo] of positionsByPair.entries()) {
      const [poolMetadata, walletEarnings] = await Promise.all([
        fetchPoolMetadata(poolAddress),
        fetchWalletEarnings(walletAddress, poolAddress)
      ]);
      const pairRecord = pairInfo as Record<string, unknown>;
      const tokenX = pairRecord.tokenX as Record<string, unknown> | undefined;
      const tokenY = pairRecord.tokenY as Record<string, unknown> | undefined;
      const mintX = tokenX?.mint as Record<string, unknown> | undefined;
      const mintY = tokenY?.mint as Record<string, unknown> | undefined;
      const metadataTokenX = poolMetadata?.token_x as Record<string, unknown> | undefined;
      const metadataTokenY = poolMetadata?.token_y as Record<string, unknown> | undefined;
      const userPositions = (pairRecord.lbPairPositionsData ?? []) as unknown[];

      for (const userPosition of userPositions) {
        const positionRecord = userPosition as Record<string, unknown>;
        const positionData = positionRecord.positionData as Record<string, unknown> | undefined;
        const tokenXMint = readString(tokenX, ["publicKey"]);
        const tokenYMint = readString(tokenY, ["publicKey"]);
        if (!tokenXMint || !tokenYMint) {
          continue;
        }

        const decimalsX = readNumber(mintX, ["decimals"], readNumber(metadataTokenX, ["decimals"], 0));
        const decimalsY = readNumber(mintY, ["decimals"], readNumber(metadataTokenY, ["decimals"], 0));
        const poolSymbols = parsePoolSymbols(poolMetadata?.name);
        const positionTokenX = await pricedToken({
          mint: tokenXMint,
          symbol: readString(
            mintX,
            ["symbol", "name"],
            readString(metadataTokenX, ["symbol", "name"], poolSymbols[0] ?? "X")
          ),
          decimals: decimalsX,
          amount: toHumanAmount(readString(positionData, ["totalXAmount"], "0"), decimalsX),
          priceUsd: readOptionalNumber(metadataTokenX, ["price"])
        });
        const positionTokenY = await pricedToken({
          mint: tokenYMint,
          symbol: readString(
            mintY,
            ["symbol", "name"],
            readString(metadataTokenY, ["symbol", "name"], poolSymbols[1] ?? "Y")
          ),
          decimals: decimalsY,
          amount: toHumanAmount(readString(positionData, ["totalYAmount"], "0"), decimalsY),
          priceUsd: readOptionalNumber(metadataTokenY, ["price"])
        });

        positions.push(
          makePosition({
            protocolId: this.protocolId,
            protocolName: this.protocolName,
            category: this.category,
            positionType: "lp",
            positionId: readString(userPosition, ["publicKey", "positionPublicKey", "address"], `${poolAddress}:${positions.length}`),
            tokens: [positionTokenX, positionTokenY],
            metadata: {
              poolAddress,
              poolName: poolMetadata?.name,
              poolBinStep: poolMetadata?.bin_step,
              poolCurrentPrice: poolMetadata?.current_price,
              poolLiquidityUsd: poolMetadata?.liquidity,
              poolFees24hUsd: poolMetadata?.fees_24h,
              claimedFeeUsd: sumNumeric(walletEarnings, "total_fee_usd_claimed"),
              claimedRewardUsd: sumNumeric(walletEarnings, "total_reward_usd_claimed"),
              claimedFeeX: sumStringAmounts(walletEarnings, "total_fee_x_claimed"),
              claimedFeeY: sumStringAmounts(walletEarnings, "total_fee_y_claimed"),
              claimedRewardX: sumStringAmounts(walletEarnings, "total_reward_x_claimed"),
              claimedRewardY: sumStringAmounts(walletEarnings, "total_reward_y_claimed"),
              lowerBinId: readNumber(positionData, ["lowerBinId"], 0),
              upperBinId: readNumber(positionData, ["upperBinId"], 0),
              version: readNumber(userPosition, ["version"], 2),
              feeX: readString(positionData, ["feeX"], "0"),
              feeY: readString(positionData, ["feeY"], "0")
            }
          })
        );
      }
    }

    return positions;
  }
}

type MeteoraDlmmSdk = {
  create: (...args: unknown[]) => Promise<unknown>;
  getAllLbPairPositionsByUser: (...args: unknown[]) => Promise<Map<string, unknown>>;
};

function getConfiguredPools(): string[] {
  return (process.env.METEORA_DLMM_POOLS ?? "")
    .split(",")
    .map((pool) => pool.trim())
    .filter(Boolean);
}

async function fetchPoolMetadata(poolAddress: string): Promise<MeteoraPoolMetadata | undefined> {
  return getOrSetCached(metadataCache, poolAddress, async () => {
    const response = await fetch(`${METEORA_DLMM_DATA_API_BASE}/pools/${encodeURIComponent(poolAddress)}`);
    if (!response.ok) {
      logger.warn({ poolAddress, status: response.status }, "meteora dlmm pool metadata unavailable");
      return undefined;
    }

    return (await response.json()) as MeteoraPoolMetadata;
  });
}

async function fetchWalletEarnings(walletAddress: string, poolAddress: string): Promise<MeteoraWalletEarning[] | undefined> {
  const cacheKey = `${walletAddress}:${poolAddress}`;
  return getOrSetCached(earningsCache, cacheKey, async () => {
    const response = await fetch(
      `${METEORA_DLMM_API_BASE}/wallet/${encodeURIComponent(walletAddress)}/${encodeURIComponent(poolAddress)}/earning`
    );
    if (!response.ok) {
      logger.warn({ walletAddress, poolAddress, status: response.status }, "meteora dlmm wallet earnings unavailable");
      return undefined;
    }

    const body = await response.json();
    return Array.isArray(body) ? (body as MeteoraWalletEarning[]) : undefined;
  });
}

async function getOrSetCached<T>(cache: Map<string, CacheEntry<T>>, key: string, loader: () => Promise<T>): Promise<T> {
  const now = Date.now();
  const cached = cache.get(key);
  if (cached && cached.expiresAt > now) {
    return cached.value;
  }

  try {
    const value = await loader();
    cache.set(key, { value, expiresAt: now + METEORA_REST_CACHE_MS });
    return value;
  } catch (error) {
    logger.warn({ key, error }, "meteora dlmm rest enrichment failed");
    const value = undefined as T;
    cache.set(key, { value, expiresAt: now + METEORA_REST_CACHE_MS });
    return value;
  }
}

function sumNumeric(records: MeteoraWalletEarning[] | undefined, key: keyof MeteoraWalletEarning): number {
  return (records ?? []).reduce((sum, record) => {
    const value = record[key];
    return sum + (typeof value === "number" && Number.isFinite(value) ? value : 0);
  }, 0);
}

function sumStringAmounts(records: MeteoraWalletEarning[] | undefined, key: keyof MeteoraWalletEarning): string {
  const total = (records ?? []).reduce((sum, record) => {
    const value = record[key];
    const parsed = typeof value === "string" ? Number(value) : Number.NaN;
    return sum + (Number.isFinite(parsed) ? parsed : 0);
  }, 0);
  return String(total);
}

function parsePoolSymbols(poolName: string | undefined): [string | undefined, string | undefined] {
  const parts = poolName?.split("-").map((part) => part.trim()).filter(Boolean) ?? [];
  return [parts[0], parts[1]];
}

function readOptionalNumber(record: unknown, keys: string[]): number | undefined {
  const value = readNumber(record, keys, Number.NaN);
  return Number.isFinite(value) ? value : undefined;
}

type CacheEntry<T> = {
  value: T;
  expiresAt: number;
};

type MeteoraPoolMetadata = {
  name?: string;
  bin_step?: number;
  current_price?: number | string;
  liquidity?: number;
  fees_24h?: number;
  token_x?: {
    symbol?: string;
    name?: string;
    decimals?: number;
    price?: number;
  };
  token_y?: {
    symbol?: string;
    name?: string;
    decimals?: number;
    price?: number;
  };
};

type MeteoraWalletEarning = {
  total_fee_usd_claimed?: number | null;
  total_fee_x_claimed?: string;
  total_fee_y_claimed?: string;
  total_reward_usd_claimed?: number | null;
  total_reward_x_claimed?: string;
  total_reward_y_claimed?: string;
};
