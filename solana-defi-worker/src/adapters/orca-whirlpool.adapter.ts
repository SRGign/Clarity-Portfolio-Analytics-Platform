import { Keypair, PublicKey, type Transaction, type VersionedTransaction } from "@solana/web3.js";
import type { Wallet } from "@coral-xyz/anchor";
import {
  getAllPositionAccountsByOwner,
  PoolUtil,
  PriceMath,
  WhirlpoolContext,
  type PositionData,
  type WhirlpoolData
} from "@orca-so/whirlpools-sdk";
import type { Mint } from "@solana/spl-token";
import type { DeFiAdapter, Position } from "../types.js";
import { connection, withRpcRetry } from "../rpc.js";
import { makePosition, pricedToken, runAdapterSafely, shortMint, toHumanAmount } from "./utils.js";

export class OrcaWhirlpoolAdapter implements DeFiAdapter {
  protocolId = "orca-whirlpool" as const;
  protocolName = "Orca Whirlpool" as const;
  category = "clmm" as const;

  async fetchPositions(walletAddress: string): Promise<Position[]> {
    return runAdapterSafely(this, walletAddress, async () => {
      const owner = new PublicKey(walletAddress);
      const ctx = WhirlpoolContext.from(connection, createReadOnlyWallet(owner));
      const positionMap = await withRpcRetry(() =>
        getAllPositionAccountsByOwner({
          ctx,
          owner,
          includesPositions: true,
          includesPositionsWithTokenExtensions: true,
          includesBundledPositions: false
        })
      );
      const entries = [
        ...positionMap.positions.entries(),
        ...positionMap.positionsWithTokenExtensions.entries()
      ];
      const positions: Position[] = [];

      for (const [positionAddress, position] of entries) {
        const whirlpool = await withRpcRetry(() => ctx.fetcher.getPool(position.whirlpool));
        if (!whirlpool || position.liquidity.isZero()) {
          continue;
        }

        const [mintA, mintB] = await Promise.all([
          ctx.fetcher.getMintInfo(whirlpool.tokenMintA),
          ctx.fetcher.getMintInfo(whirlpool.tokenMintB)
        ]);
        if (!mintA || !mintB) {
          continue;
        }

        positions.push(await this.normalizePosition(positionAddress, position, whirlpool, mintA, mintB));
      }

      return positions;
    });
  }

  private async normalizePosition(
    positionAddress: string,
    position: PositionData,
    whirlpool: WhirlpoolData,
    mintA: Mint,
    mintB: Mint
  ): Promise<Position> {
    const rawAmounts = PoolUtil.getTokenAmountsFromLiquidity(
      position.liquidity,
      whirlpool.sqrtPrice,
      PriceMath.tickIndexToSqrtPriceX64(position.tickLowerIndex),
      PriceMath.tickIndexToSqrtPriceX64(position.tickUpperIndex),
      false
    );
    const mintAAddress = whirlpool.tokenMintA.toBase58();
    const mintBAddress = whirlpool.tokenMintB.toBase58();
    const tokenA = await pricedToken({
      mint: mintAAddress,
      symbol: shortMint(mintAAddress),
      decimals: mintA.decimals,
      amount: toHumanAmount(rawAmounts.tokenA.toString(), mintA.decimals)
    });
    const tokenB = await pricedToken({
      mint: mintBAddress,
      symbol: shortMint(mintBAddress),
      decimals: mintB.decimals,
      amount: toHumanAmount(rawAmounts.tokenB.toString(), mintB.decimals)
    });
    const pendingRewards = await Promise.all(
      position.rewardInfos
        .filter((rewardInfo, index) => {
          const poolReward = whirlpool.rewardInfos[index];
          return Boolean(poolReward && !rewardInfo.amountOwed.isZero() && PoolUtil.isRewardInitialized(poolReward));
        })
        .map((rewardInfo, index) => {
          const rewardMint = whirlpool.rewardInfos[index]?.mint.toBase58() ?? "";
          return pricedToken({
            mint: rewardMint,
            symbol: shortMint(rewardMint),
            decimals: 0,
            amount: Number(rewardInfo.amountOwed.toString())
          });
        })
    );

    return makePosition({
      protocolId: this.protocolId,
      protocolName: this.protocolName,
      category: this.category,
      positionType: "lp",
      positionId: positionAddress,
      tokens: [tokenA, tokenB],
      pendingRewards,
      metadata: {
        whirlpool: position.whirlpool.toBase58(),
        positionMint: position.positionMint.toBase58(),
        tickLowerIndex: position.tickLowerIndex,
        tickUpperIndex: position.tickUpperIndex,
        tickCurrentIndex: whirlpool.tickCurrentIndex,
        tickSpacing: whirlpool.tickSpacing,
        feeRate: whirlpool.feeRate,
        feeOwedA: position.feeOwedA.toString(),
        feeOwedB: position.feeOwedB.toString()
      }
    });
  }
}

function createReadOnlyWallet(publicKey: PublicKey): Wallet {
  return {
    payer: Keypair.generate(),
    publicKey,
    signTransaction: async <T extends Transaction | VersionedTransaction>(transaction: T): Promise<T> => transaction,
    signAllTransactions: async <T extends Transaction | VersionedTransaction>(transactions: T[]): Promise<T[]> => transactions
  };
}
