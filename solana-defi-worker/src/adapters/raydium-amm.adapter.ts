import { PublicKey } from "@solana/web3.js";
import type { DeFiAdapter, Position } from "../types.js";
import { connection, withRpcRetry } from "../rpc.js";
import { logger } from "../logger.js";
import { makePosition, pricedToken, readNumber, readString, runAdapterSafely } from "./utils.js";

export class RaydiumAmmAdapter implements DeFiAdapter {
  protocolId = "raydium-amm" as const;
  protocolName = "Raydium AMM" as const;
  category = "amm" as const;

  async fetchPositions(walletAddress: string): Promise<Position[]> {
    return runAdapterSafely(this, walletAddress, async () => {
      const sdk = await import("@raydium-io/raydium-sdk-v2");
      const owner = new PublicKey(walletAddress);
      const raydium = await sdk.Raydium.load({ connection, owner, cluster: "mainnet" });
      const tokenAccounts = await withRpcRetry(() => connection.getParsedTokenAccountsByOwner(owner, { programId: new PublicKey("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA") }));
      const lpMints = tokenAccounts.value
        .map((account) => account.account.data.parsed.info)
        .filter((info) => Number(info.tokenAmount?.uiAmount ?? 0) > 0)
        .map((info) => ({ mint: info.mint as string, amount: Number(info.tokenAmount.uiAmount) }));
      const positions: Position[] = [];

      for (const lpToken of lpMints) {
        const pool = await findRaydiumPoolByLpMint(raydium, lpToken.mint);
        if (!pool) {
          continue;
        }

        const mintA = readString(pool, ["mintA", "baseMint", "tokenAMint"]);
        const mintB = readString(pool, ["mintB", "quoteMint", "tokenBMint"]);
        if (!mintA || !mintB) {
          continue;
        }

        const totalLp = readNumber(pool, ["lpSupply", "supply"], 0);
        const share = totalLp > 0 ? lpToken.amount / totalLp : 0;
        const tokenA = await pricedToken({
          mint: mintA,
          symbol: readString(pool, ["symbolA", "baseSymbol"], "A"),
          decimals: readNumber(pool, ["decimalsA", "baseDecimal"], 0),
          amount: readNumber(pool, ["reserveA", "baseReserve"], 0) * share
        });
        const tokenB = await pricedToken({
          mint: mintB,
          symbol: readString(pool, ["symbolB", "quoteSymbol"], "B"),
          decimals: readNumber(pool, ["decimalsB", "quoteDecimal"], 0),
          amount: readNumber(pool, ["reserveB", "quoteReserve"], 0) * share
        });

        positions.push(
          makePosition({
            protocolId: this.protocolId,
            protocolName: this.protocolName,
            category: this.category,
            positionType: "lp",
            positionId: readString(pool, ["id", "poolId"], lpToken.mint),
            tokens: [tokenA, tokenB],
            metadata: {
              lpMint: lpToken.mint,
              lpAmount: lpToken.amount,
              share
            }
          })
        );
      }

      if (positions.length === 0) {
        logger.info({ protocolId: this.protocolId, walletAddress }, "no Raydium AMM LP positions discovered");
      }

      return positions;
    });
  }
}

async function findRaydiumPoolByLpMint(raydium: unknown, lpMint: string): Promise<unknown> {
  const typedRaydium = raydium as {
    api?: {
      fetchPoolByMints?: (args: { mint1: string; mint2?: string }) => Promise<unknown[]>;
      fetchPoolById?: (args: { ids: string }) => Promise<unknown[]>;
    };
    liquidity?: {
      getPoolInfoFromRpc?: (id: string) => Promise<unknown>;
    };
  };

  try {
    const api = typedRaydium.api as Record<string, unknown> | undefined;
    if (api && typeof api.fetchPoolByLpMint === "function") {
      const pools = await (api.fetchPoolByLpMint as (args: { lpMint: string }) => Promise<unknown[]>)({ lpMint });
      return pools?.[0];
    }
  } catch {
    return undefined;
  }

  return undefined;
}
