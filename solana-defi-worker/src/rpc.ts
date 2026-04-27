import { Connection } from "@solana/web3.js";
import { createSolanaRpc } from "@solana/kit";

const endpoint =
  process.env.SOLANA_RPC_URL ||
  process.env.RPC_FAST_URL ||
  process.env.HELIUS_RPC_URL ||
  "https://api.mainnet-beta.solana.com";
const rpcRequestsPerSecond = readPositiveInteger(process.env.SOLANA_RPC_RPS, 50);

const rpcLimiter = createRateLimiter(rpcRequestsPerSecond);
installRpcFetchLimiter(endpoint, rpcLimiter);

export const connection = new Connection(endpoint, {
  commitment: "confirmed",
  confirmTransactionInitialTimeout: 60_000,
  fetchMiddleware: async (_url, _options, fetch) => {
    await rpcLimiter();
    await fetch(_url, _options);
  }
});

export const kitRpc = createSolanaRpc(endpoint);

export async function withRpcRetry<T>(operation: () => Promise<T>, maxAttempts = 3): Promise<T> {
  let lastError: unknown;

  for (let attempt = 1; attempt <= maxAttempts; attempt += 1) {
    try {
      return await operation();
    } catch (error) {
      lastError = error;
      const message = error instanceof Error ? error.message : String(error);
      const retryable = message.includes("429") || message.toLowerCase().includes("too many requests");

      if (!retryable || attempt === maxAttempts) {
        throw error;
      }

      await new Promise((resolve) => setTimeout(resolve, 1000 * 2 ** (attempt - 1)));
    }
  }

  throw lastError;
}

function readPositiveInteger(value: string | undefined, fallback: number): number {
  const parsed = Number(value);
  return Number.isInteger(parsed) && parsed > 0 ? parsed : fallback;
}

function createRateLimiter(requestsPerSecond: number): () => Promise<void> {
  const intervalMs = Math.ceil(1000 / requestsPerSecond);
  let nextAvailableAt = 0;
  let queue = Promise.resolve();

  return async () => {
    queue = queue.then(async () => {
      const now = Date.now();
      const waitMs = Math.max(0, nextAvailableAt - now);
      nextAvailableAt = Math.max(now, nextAvailableAt) + intervalMs;

      if (waitMs > 0) {
        await new Promise((resolve) => setTimeout(resolve, waitMs));
      }
    });

    await queue;
  };
}

function installRpcFetchLimiter(rpcEndpoint: string, limitRpcRequest: () => Promise<void>): void {
  const originalFetch = globalThis.fetch.bind(globalThis);
  type FetchParameters = Parameters<typeof globalThis.fetch>;

  globalThis.fetch = async (input: FetchParameters[0], init?: FetchParameters[1]): Promise<Response> => {
    if (!isRpcRequest(input, rpcEndpoint)) {
      return originalFetch(input, init);
    }

    await limitRpcRequest();
    return originalFetch(input, init);
  };
}

function isRpcRequest(input: Parameters<typeof globalThis.fetch>[0], rpcEndpoint: string): boolean {
  const requestUrl =
    typeof input === "string"
      ? input
      : input instanceof URL
        ? input.toString()
        : input.url;

  return requestUrl === rpcEndpoint;
}
