import type { DeFiAdapter, Position, PositionToken, PositionType } from "../types.js";
import { getPrice } from "../pricing.js";
import { logger } from "../logger.js";

export async function runAdapterSafely(
  adapter: Pick<DeFiAdapter, "protocolId" | "protocolName">,
  walletAddress: string,
  operation: () => Promise<Position[]>
): Promise<Position[]> {
  try {
    const positions = await operation();
    return positions.filter(isValidPosition);
  } catch (error) {
    logger.warn({ protocolId: adapter.protocolId, walletAddress, error: formatError(error) }, "adapter failed");
    throw error;
  }
}

export async function pricedToken(input: {
  mint: string;
  symbol?: string;
  decimals?: number;
  rawAmount?: string | number | bigint;
  amount?: number;
  priceUsd?: number;
}): Promise<PositionToken> {
  const decimals = input.decimals ?? 0;
  const amount = input.amount ?? toHumanAmount(input.rawAmount ?? 0, decimals);
  const priceUsd = input.priceUsd ?? (await getPrice(input.mint));

  return {
    mint: input.mint,
    symbol: input.symbol ?? shortMint(input.mint),
    decimals,
    amount,
    priceUsd,
    valueUsd: amount * priceUsd
  };
}

export function positionTotal(tokens: PositionToken[], pendingRewards: PositionToken[] = []): number {
  return [...tokens, ...pendingRewards].reduce((sum, token) => sum + token.valueUsd, 0);
}

export function makePosition(input: {
  protocolId: string;
  protocolName: string;
  category: Position["category"];
  positionType: PositionType;
  positionId: string;
  tokens: PositionToken[];
  pendingRewards?: PositionToken[];
  metadata?: Record<string, unknown>;
}): Position {
  return {
    protocolId: input.protocolId,
    protocolName: input.protocolName,
    category: input.category,
    positionType: input.positionType,
    positionId: input.positionId,
    tokens: input.tokens,
    totalValueUsd: positionTotal(input.tokens, input.pendingRewards),
    pendingRewards: input.pendingRewards,
    metadata: input.metadata
  };
}

export function toNumber(value: unknown, fallback = 0): number {
  if (typeof value === "number") {
    return Number.isFinite(value) ? value : fallback;
  }
  if (typeof value === "bigint") {
    return Number(value);
  }
  if (typeof value === "string") {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : fallback;
  }
  if (value && typeof value === "object" && "toString" in value) {
    const parsed = Number((value as { toString(): string }).toString());
    return Number.isFinite(parsed) ? parsed : fallback;
  }
  return fallback;
}

export function toHumanAmount(rawAmount: string | number | bigint, decimals: number): number {
  const amount = toNumber(rawAmount);
  return amount / 10 ** decimals;
}

export function readString(record: unknown, keys: string[], fallback = ""): string {
  const object = record as Record<string, unknown>;
  for (const key of keys) {
    const value = object?.[key];
    if (typeof value === "string" && value.length > 0) {
      return value;
    }
    if (value && typeof value === "object" && "toBase58" in value) {
      return (value as { toBase58(): string }).toBase58();
    }
    if (value && typeof value === "object" && "toString" in value) {
      return (value as { toString(): string }).toString();
    }
  }
  return fallback;
}

export function readNumber(record: unknown, keys: string[], fallback = 0): number {
  const object = record as Record<string, unknown>;
  for (const key of keys) {
    const value = object?.[key];
    const numberValue = toNumber(value, Number.NaN);
    if (Number.isFinite(numberValue)) {
      return numberValue;
    }
  }
  return fallback;
}

export function shortMint(mint: string): string {
  if (mint.length <= 8) {
    return mint;
  }
  return `${mint.slice(0, 4)}...${mint.slice(-4)}`;
}

export function isValidPosition(position: Position): boolean {
  return Boolean(
    position.protocolId &&
      position.protocolName &&
      position.category &&
      position.positionType &&
      position.positionId &&
      Array.isArray(position.tokens) &&
      typeof position.totalValueUsd === "number"
  );
}

export function formatError(error: unknown): Record<string, unknown> {
  if (error instanceof Error) {
    return {
      name: error.name,
      message: error.message,
      stack: error.stack
    };
  }
  return { message: String(error) };
}
