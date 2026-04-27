import { PublicKey } from "@solana/web3.js";
import type { DeFiAdapter, Position } from "../types.js";
import { connection, withRpcRetry } from "../rpc.js";
import { logger } from "../logger.js";
import { makePosition, pricedToken, readNumber, readString, runAdapterSafely } from "./utils.js";

export class KaminoLiquidityAdapter implements DeFiAdapter {
  protocolId = "kamino-liquidity" as const;
  protocolName = "Kamino Liquidity" as const;
  category = "yield" as const;

  async fetchPositions(walletAddress: string): Promise<Position[]> {
    return runAdapterSafely(this, walletAddress, async () => {
      const sdkModule = await import("@kamino-finance/kliquidity-sdk");
      const client = createKaminoLiquidityClient(sdkModule, walletAddress);
      if (!client?.getUserPositions) {
        logger.warn({ protocolId: this.protocolId }, "Kamino liquidity SDK discovery method unavailable");
        return [];
      }

      const getUserPositions = client.getUserPositions;
      const rawPositions = await withRpcRetry(() => getUserPositions(new PublicKey(walletAddress)));
      const positions: Position[] = [];

      for (const rawPosition of rawPositions ?? []) {
        const mintA = readString(rawPosition, ["mintA", "tokenAMint", "tokenMintA"]);
        const mintB = readString(rawPosition, ["mintB", "tokenBMint", "tokenMintB"]);
        if (!mintA || !mintB) {
          continue;
        }

        const tokenA = await pricedToken({
          mint: mintA,
          symbol: readString(rawPosition, ["symbolA", "tokenASymbol"], "A"),
          decimals: readNumber(rawPosition, ["decimalsA", "tokenADecimals"], 0),
          amount: readNumber(rawPosition, ["amountA", "tokenAAmount"], 0)
        });
        const tokenB = await pricedToken({
          mint: mintB,
          symbol: readString(rawPosition, ["symbolB", "tokenBSymbol"], "B"),
          decimals: readNumber(rawPosition, ["decimalsB", "tokenBDecimals"], 0),
          amount: readNumber(rawPosition, ["amountB", "tokenBAmount"], 0)
        });

        positions.push(
          makePosition({
            protocolId: this.protocolId,
            protocolName: this.protocolName,
            category: this.category,
            positionType: "lp",
            positionId: readString(rawPosition, ["position", "positionAddress", "pubkey", "id"], `${walletAddress}:kamino-liquidity:${positions.length}`),
            tokens: [tokenA, tokenB],
            metadata: {
              vault: readString(rawPosition, ["vault", "vaultAddress"]),
              underlyingProtocol: readString(rawPosition, ["underlyingProtocol", "dex"], "unknown"),
              feeTier: readNumber(rawPosition, ["feeTier", "feeRate"], 0)
            }
          })
        );
      }

      return positions;
    });
  }
}

function createKaminoLiquidityClient(module: Record<string, unknown>, walletAddress: string): { getUserPositions?: (owner: PublicKey) => Promise<unknown[]> } | undefined {
  const Constructor = module.KaminoLiquidityClient ?? module.KaminoManager ?? module.default;
  if (typeof Constructor === "function") {
    const Ctor = Constructor as new (...args: unknown[]) => { getUserPositions?: (owner: PublicKey) => Promise<unknown[]> };
    return new Ctor(connection, new PublicKey(walletAddress));
  }
  if (typeof module.getUserPositions === "function") {
    return { getUserPositions: module.getUserPositions as (owner: PublicKey) => Promise<unknown[]> };
  }
  return undefined;
}
