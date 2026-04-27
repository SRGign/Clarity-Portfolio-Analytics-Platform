import NodeCache from "node-cache";
import type { PositionsResponse } from "./types.js";

const positionCache = new NodeCache({ stdTTL: 300, checkperiod: 60, useClones: false });
const priceCache = new NodeCache({ stdTTL: 60, checkperiod: 30, useClones: false });

export function positionCacheKey(walletAddress: string, protocols?: string[]): string {
  const protocolKey = protocols?.length ? [...protocols].sort().join(",") : "all";
  return `${walletAddress}:${protocolKey}`;
}

export function getCachedPositions(key: string): PositionsResponse | undefined {
  return positionCache.get<PositionsResponse>(key);
}

export function setCachedPositions(key: string, value: PositionsResponse): void {
  positionCache.set(key, value);
}

export function getCachedPrice(mint: string): number | undefined {
  return priceCache.get<number>(mint);
}

export function setCachedPrice(mint: string, value: number): void {
  priceCache.set(mint, value);
}
