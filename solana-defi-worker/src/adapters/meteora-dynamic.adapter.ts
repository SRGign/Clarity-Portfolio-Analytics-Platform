import { PublicKey } from "@solana/web3.js";
import type { DeFiAdapter, Position } from "../types.js";
import { connection, withRpcRetry } from "../rpc.js";
import { logger } from "../logger.js";
import { makePosition, pricedToken, readNumber, readString, runAdapterSafely } from "./utils.js";

export class MeteoraDynamicAdapter implements DeFiAdapter {
  protocolId = "meteora-dynamic" as const;
  protocolName = "Meteora Dynamic AMM" as const;
  category = "amm" as const;

  async fetchPositions(walletAddress: string): Promise<Position[]> {
    return runAdapterSafely(this, walletAddress, async () => {
      const sdkModule = await import("@mercurial-finance/dynamic-amm-sdk");
      const client = createDynamicAmmClient(sdkModule);
      if (!client?.getUserPoolPositions) {
        logger.warn({ protocolId: this.protocolId }, "Meteora dynamic SDK user-position discovery method unavailable");
        return [];
      }

      const getUserPoolPositions = client.getUserPoolPositions;
      const userPositions = await withRpcRetry(() => getUserPoolPositions(new PublicKey(walletAddress)));
      const positions: Position[] = [];

      for (const rawPosition of userPositions ?? []) {
        const mintA = readString(rawPosition, ["mintA", "tokenAMint", "baseMint"]);
        const mintB = readString(rawPosition, ["mintB", "tokenBMint", "quoteMint"]);
        if (!mintA || !mintB) {
          continue;
        }

        positions.push(
          makePosition({
            protocolId: this.protocolId,
            protocolName: this.protocolName,
            category: this.category,
            positionType: "lp",
            positionId: readString(rawPosition, ["pool", "poolAddress", "id"], `${walletAddress}:meteora-dynamic:${positions.length}`),
            tokens: [
              await pricedToken({
                mint: mintA,
                symbol: readString(rawPosition, ["symbolA", "baseSymbol"], "A"),
                decimals: readNumber(rawPosition, ["decimalsA", "baseDecimals"], 0),
                amount: readNumber(rawPosition, ["amountA", "baseAmount"], 0)
              }),
              await pricedToken({
                mint: mintB,
                symbol: readString(rawPosition, ["symbolB", "quoteSymbol"], "B"),
                decimals: readNumber(rawPosition, ["decimalsB", "quoteDecimals"], 0),
                amount: readNumber(rawPosition, ["amountB", "quoteAmount"], 0)
              })
            ],
            metadata: {
              lpMint: readString(rawPosition, ["lpMint", "poolLpMint"]),
              lpAmount: readNumber(rawPosition, ["lpAmount"], 0)
            }
          })
        );
      }

      return positions;
    });
  }
}

function createDynamicAmmClient(module: Record<string, unknown>): { getUserPoolPositions?: (owner: PublicKey) => Promise<unknown[]> } | undefined {
  const Constructor = module.DynamicAmmClient ?? module.MercurialDynamicAmm ?? module.default;
  if (typeof Constructor === "function") {
    const Ctor = Constructor as new (...args: unknown[]) => { getUserPoolPositions?: (owner: PublicKey) => Promise<unknown[]> };
    return new Ctor(connection);
  }
  if (typeof module.getUserPoolPositions === "function") {
    return { getUserPoolPositions: module.getUserPoolPositions as (owner: PublicKey) => Promise<unknown[]> };
  }
  return undefined;
}
