import { PublicKey } from "@solana/web3.js";
import { getCachedPositions, positionCacheKey, setCachedPositions } from "./cache.js";
import { adapters } from "./adapters/registry.js";
import { formatError } from "./adapters/utils.js";
import type { DeFiAdapter, PositionsRequest, PositionsResponse, ProtocolDescriptor } from "./types.js";

export function listProtocols(): ProtocolDescriptor[] {
  return adapters.map(({ protocolId, protocolName, category }) => ({ protocolId, protocolName, category }));
}

export async function fetchPositions(request: PositionsRequest): Promise<PositionsResponse> {
  validateWalletAddress(request.walletAddress);

  const selectedAdapters = resolveAdapters(request.protocols);
  const cacheKey = positionCacheKey(
    request.walletAddress,
    selectedAdapters.map((adapter) => adapter.protocolId)
  );
  const cached = getCachedPositions(cacheKey);

  if (cached) {
    return cached;
  }

  const results = await Promise.all(
    selectedAdapters.map(async (adapter) => {
      try {
        return {
          adapter,
          status: "fulfilled" as const,
          positions: await adapter.fetchPositions(request.walletAddress)
        };
      } catch (error) {
        return {
          adapter,
          status: "rejected" as const,
          error
        };
      }
    })
  );

  const response: PositionsResponse = {
    walletAddress: request.walletAddress,
    fetchedAt: new Date().toISOString(),
    positions: [],
    errors: []
  };

  for (const result of results) {
    if (result.status === "fulfilled") {
      response.positions.push(...result.positions);
    } else {
      const error = formatError(result.error);
      response.errors.push({
        protocolId: result.adapter.protocolId,
        message: typeof error.message === "string" ? error.message : String(result.error)
      });
    }
  }

  setCachedPositions(cacheKey, response);
  return response;
}

function resolveAdapters(protocols?: string[]): DeFiAdapter[] {
  if (!protocols || protocols.length === 0) {
    return adapters;
  }

  const requested = new Set(protocols);
  return adapters.filter((adapter) => requested.has(adapter.protocolId));
}

function validateWalletAddress(walletAddress: string): void {
  try {
    new PublicKey(walletAddress);
  } catch {
    throw new Error("Invalid Solana walletAddress");
  }
}
