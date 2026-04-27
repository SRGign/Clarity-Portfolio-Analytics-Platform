import { PublicKey } from "@solana/web3.js";
import type { DeFiAdapter, Position } from "../types.js";
import { connection, withRpcRetry } from "../rpc.js";
import { makePosition, pricedToken, readNumber, readString, runAdapterSafely } from "./utils.js";

export class RaydiumClmmAdapter implements DeFiAdapter {
  protocolId = "raydium-clmm" as const;
  protocolName = "Raydium CLMM" as const;
  category = "clmm" as const;

  async fetchPositions(walletAddress: string): Promise<Position[]> {
    return runAdapterSafely(this, walletAddress, async () => {
      const sdk = await import("@raydium-io/raydium-sdk-v2");
      const owner = new PublicKey(walletAddress);
      const raydium = await sdk.Raydium.load({ connection, owner, cluster: "mainnet" });
      const programId = sdk.CLMM_PROGRAM_ID ?? new PublicKey("CAMMCzo5YL8w4VFF8KVHrK22GGUsp5VTaW7grrKgrWqK");
      const ownerPositions = (await withRpcRetry(() => raydium.clmm.getOwnerPositionInfo({ programId, owner }))) as unknown[];
      const positions: Position[] = [];
      const poolInfoCache = new Map<string, unknown>();

      for (const position of ownerPositions) {
        const poolId = readString(position, ["poolId", "poolAddress"]);
        const poolInfo = poolId ? await getPoolInfo(raydium, poolId, poolInfoCache) : undefined;
        const poolRpcInfo = poolId ? await withRpcRetry(() => raydium.clmm.getRpcClmmPoolInfo({ poolId })) : undefined;
        const mintARecord = readRecord(poolInfo, ["mintA"]);
        const mintBRecord = readRecord(poolInfo, ["mintB"]);
        const mintA = readString(position, ["mintA", "mintMintA", "tokenMintA", "tokenA"]) || readString(mintARecord, ["address"]);
        const mintB = readString(position, ["mintB", "mintMintB", "tokenMintB", "tokenB"]) || readString(mintBRecord, ["address"]);
        if (!mintA || !mintB) {
          continue;
        }

        const decimalsA = readNumber(position, ["decimalsA", "mintDecimalsA", "tokenDecimalA"], readNumber(mintARecord, ["decimals"], 0));
        const decimalsB = readNumber(position, ["decimalsB", "mintDecimalsB", "tokenDecimalB"], readNumber(mintBRecord, ["decimals"], 0));
        const computedAmounts = computePositionAmounts(sdk, position, poolRpcInfo, decimalsA, decimalsB);
        const tokenA = await pricedToken({
          mint: mintA,
          symbol: readString(position, ["symbolA", "tokenSymbolA"], readString(mintARecord, ["symbol"], "A")),
          decimals: decimalsA,
          amount: readNumber(position, ["amountA", "tokenAmountA", "depositedAmountA"], computedAmounts.amountA)
        });
        const tokenB = await pricedToken({
          mint: mintB,
          symbol: readString(position, ["symbolB", "tokenSymbolB"], readString(mintBRecord, ["symbol"], "B")),
          decimals: decimalsB,
          amount: readNumber(position, ["amountB", "tokenAmountB", "depositedAmountB"], computedAmounts.amountB)
        });

        positions.push(
          makePosition({
            protocolId: this.protocolId,
            protocolName: this.protocolName,
            category: this.category,
            positionType: "lp",
            positionId: readString(position, ["nftMint", "positionNftMint", "id", "pubkey"], `${walletAddress}:raydium-clmm`),
            tokens: [tokenA, tokenB],
            metadata: {
              poolId,
              tickLower: readNumber(position, ["tickLower", "tickLowerIndex"], 0),
              tickUpper: readNumber(position, ["tickUpper", "tickUpperIndex"], 0),
              liquidity: readString(position, ["liquidity"], "0"),
              feeGrowthInsideLastX64A: readString(position, ["feeGrowthInsideLastX64A"], "0"),
              feeGrowthInsideLastX64B: readString(position, ["feeGrowthInsideLastX64B"], "0")
            }
          })
        );
      }

      return positions;
    });
  }
}

async function getPoolInfo(raydium: unknown, poolId: string, cache: Map<string, unknown>): Promise<unknown> {
  if (cache.has(poolId)) {
    return cache.get(poolId);
  }

  const typedRaydium = raydium as { api?: { fetchPoolById?: (args: { ids: string }) => Promise<unknown[]> } };
  const poolInfo = (await typedRaydium.api?.fetchPoolById?.({ ids: poolId }))?.[0];
  cache.set(poolId, poolInfo);
  return poolInfo;
}

function computePositionAmounts(
  sdk: Record<string, unknown>,
  position: unknown,
  poolRpcInfo: unknown,
  decimalsA: number,
  decimalsB: number
): { amountA: number; amountB: number } {
  const record = position as Record<string, unknown>;
  const rpcRecord = poolRpcInfo as Record<string, unknown>;
  const liquidity = record.liquidity;
  const sqrtPriceX64 = rpcRecord.sqrtPriceX64;
  const tickLower = readNumber(position, ["tickLower", "tickLowerIndex"], Number.NaN);
  const tickUpper = readNumber(position, ["tickUpper", "tickUpperIndex"], Number.NaN);
  const SqrtPriceMath = sdk.SqrtPriceMath as
    | { getSqrtPriceX64FromTick?: (tick: number) => unknown }
    | undefined;
  const LiquidityMath = sdk.LiquidityMath as
    | { getAmountsFromLiquidity?: (sqrtPriceX64: unknown, sqrtLower: unknown, sqrtUpper: unknown, liquidity: unknown, roundUp: boolean) => { amountA: unknown; amountB: unknown } }
    | undefined;

  if (!liquidity || !sqrtPriceX64 || !Number.isFinite(tickLower) || !Number.isFinite(tickUpper)) {
    return { amountA: 0, amountB: 0 };
  }

  const sqrtLower = SqrtPriceMath?.getSqrtPriceX64FromTick?.(tickLower);
  const sqrtUpper = SqrtPriceMath?.getSqrtPriceX64FromTick?.(tickUpper);
  if (!sqrtLower || !sqrtUpper || !LiquidityMath?.getAmountsFromLiquidity) {
    return { amountA: 0, amountB: 0 };
  }

  const amounts = LiquidityMath.getAmountsFromLiquidity(sqrtPriceX64, sqrtLower, sqrtUpper, liquidity, false);
  return {
    amountA: Number(amounts.amountA?.toString?.() ?? 0) / 10 ** decimalsA,
    amountB: Number(amounts.amountB?.toString?.() ?? 0) / 10 ** decimalsB
  };
}

function readRecord(record: unknown, keys: string[]): unknown {
  const object = record as Record<string, unknown>;
  for (const key of keys) {
    const value = object?.[key];
    if (value && typeof value === "object") {
      return value;
    }
  }
  return undefined;
}
