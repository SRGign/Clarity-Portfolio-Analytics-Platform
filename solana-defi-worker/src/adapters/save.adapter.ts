import { PublicKey, type AccountInfo } from "@solana/web3.js";
import type { DeFiAdapter, Position } from "../types.js";
import { connection, withRpcRetry } from "../rpc.js";
import { makePosition, pricedToken, runAdapterSafely, shortMint } from "./utils.js";

const WAD_DECIMALS = 18;

export class SaveAdapter implements DeFiAdapter {
  protocolId = "save" as const;
  protocolName = "Save Protocol" as const;
  category = "lending" as const;

  async fetchPositions(walletAddress: string): Promise<Position[]> {
    return runAdapterSafely(this, walletAddress, async () => {
      const sdk = await loadSaveParsers();
      const owner = new PublicKey(walletAddress);
      const poolAddresses = resolvePoolAddresses(sdk.MAIN_POOL_ADDRESS);
      const positions: Position[] = [];

      for (const poolAddress of poolAddresses) {
        const pool = new PublicKey(poolAddress);
        const rawObligations = await withRpcRetry(() =>
          connection.getProgramAccounts(sdk.SOLEND_PRODUCTION_PROGRAM_ID, {
            commitment: "confirmed",
            filters: [
              { dataSize: sdk.OBLIGATION_SIZE },
              { memcmp: { offset: 42, bytes: owner.toBase58() } },
              { memcmp: { offset: 10, bytes: pool.toBase58() } }
            ]
          })
        );
        const obligations = rawObligations.map((account) => sdk.parseObligation(account.pubkey, account.account).info);
        const reserveAddresses = [
          ...new Set(
            obligations.flatMap((obligation) => [
              ...obligation.deposits.map((deposit) => deposit.depositReserve.toBase58()),
              ...obligation.borrows.map((borrow) => borrow.borrowReserve.toBase58())
            ])
          )
        ];
        const reserveInfos = await withRpcRetry(() =>
          connection.getMultipleAccountsInfo(reserveAddresses.map((address) => new PublicKey(address)), "confirmed")
        );
        const reserves = new Map<string, SaveReserve>();
        reserveInfos.forEach((accountInfo, index) => {
          const reserveAddress = reserveAddresses[index];
          if (!accountInfo || !reserveAddress) {
            return;
          }
          reserves.set(reserveAddress, sdk.parseReserve(new PublicKey(reserveAddress), accountInfo).info);
        });

        for (const obligation of obligations) {
          positions.push(...(await this.normalizeObligation(obligation, reserves, poolAddress)));
        }
      }

      return positions;
    });
  }

  private async normalizeObligation(
    obligation: SaveObligation,
    reserves: Map<string, SaveReserve>,
    poolAddress: string
  ): Promise<Position[]> {
    const positions: Position[] = [];
    const obligationAddress = obligation.pubkey.toBase58();
    const metadata = {
      obligationAddress,
      poolAddress,
      depositedValueUsd: wadToNumber(obligation.depositedValue),
      borrowedValueUsd: wadToNumber(obligation.borrowedValue),
      borrowLimitUsd: wadToNumber(obligation.allowedBorrowValue),
      unhealthyBorrowValueUsd: wadToNumber(obligation.unhealthyBorrowValue),
      closeable: obligation.closeable
    };

    for (const deposit of obligation.deposits) {
      const reserve = reserves.get(deposit.depositReserve.toBase58());
      if (!reserve) {
        continue;
      }
      const mint = reserve.liquidity.mintPubkey.toBase58();
      const valueUsd = wadToNumber(deposit.marketValue);
      const priceUsd = reservePriceUsd(reserve);
      const amount = priceUsd > 0 ? valueUsd / priceUsd : 0;

      positions.push(
        makePosition({
          protocolId: this.protocolId,
          protocolName: this.protocolName,
          category: this.category,
          positionType: "deposit",
          positionId: `${obligationAddress}:deposit:${deposit.depositReserve.toBase58()}`,
          tokens: [
            await pricedToken({
              mint,
              symbol: shortMint(mint),
              decimals: reserve.liquidity.mintDecimals,
              amount,
              priceUsd
            })
          ],
          metadata: {
            ...metadata,
            reserveAddress: deposit.depositReserve.toBase58(),
            collateralAmountRaw: deposit.depositedAmount.toString(),
            loanToValueRatio: reserve.config.loanToValueRatio,
            liquidationThreshold: reserve.config.liquidationThreshold
          }
        })
      );
    }

    for (const borrow of obligation.borrows) {
      const reserve = reserves.get(borrow.borrowReserve.toBase58());
      if (!reserve) {
        continue;
      }
      const mint = reserve.liquidity.mintPubkey.toBase58();
      const valueUsd = wadToNumber(borrow.marketValue);
      const priceUsd = reservePriceUsd(reserve);
      const amount = wadToNumber(borrow.borrowedAmountWads, reserve.liquidity.mintDecimals);

      positions.push(
        makePosition({
          protocolId: this.protocolId,
          protocolName: this.protocolName,
          category: this.category,
          positionType: "borrow",
          positionId: `${obligationAddress}:borrow:${borrow.borrowReserve.toBase58()}`,
          tokens: [
            {
              ...(await pricedToken({
                mint,
                symbol: shortMint(mint),
                decimals: reserve.liquidity.mintDecimals,
                amount: -amount,
                priceUsd: priceUsd || (amount ? valueUsd / amount : 0)
              })),
              valueUsd: -Math.abs(valueUsd)
            }
          ],
          metadata: {
            ...metadata,
            reserveAddress: borrow.borrowReserve.toBase58(),
            cumulativeBorrowRateWads: borrow.cumulativeBorrowRateWads.toString(),
            loanToValueRatio: reserve.config.loanToValueRatio,
            liquidationThreshold: reserve.config.liquidationThreshold,
            borrowWeight: reserve.config.borrowWeight
          }
        })
      );
    }

    return positions;
  }
}

function resolvePoolAddresses(mainPoolAddress: PublicKey): string[] {
  const configured = (process.env.SAVE_POOL_ADDRESSES ?? "")
    .split(",")
    .map((address) => address.trim())
    .filter(Boolean);

  return configured.length > 0 ? configured : [mainPoolAddress.toBase58()];
}

async function loadSaveParsers(): Promise<SaveParsers> {
  const [constants, obligation, reserve] = await Promise.all([
    import("@solendprotocol/solend-sdk/core/constants.js"),
    import("@solendprotocol/solend-sdk/state/obligation.js"),
    import("@solendprotocol/solend-sdk/state/reserve.js")
  ]);

  return {
    MAIN_POOL_ADDRESS: constants.MAIN_POOL_ADDRESS,
    SOLEND_PRODUCTION_PROGRAM_ID: constants.SOLEND_PRODUCTION_PROGRAM_ID,
    OBLIGATION_SIZE: obligation.OBLIGATION_SIZE,
    parseObligation: obligation.parseObligation,
    parseReserve: reserve.parseReserve
  } as SaveParsers;
}

function reservePriceUsd(reserve: SaveReserve): number {
  return wadToNumber(reserve.liquidity.marketPrice);
}

function wadToNumber(value: { toString(): string }, decimals = WAD_DECIMALS): number {
  const parsed = Number(value.toString());
  return Number.isFinite(parsed) ? parsed / 10 ** decimals : 0;
}

type SaveParsers = {
  MAIN_POOL_ADDRESS: PublicKey;
  SOLEND_PRODUCTION_PROGRAM_ID: PublicKey;
  OBLIGATION_SIZE: number;
  parseObligation: (pubkey: PublicKey, account: AccountInfo<Buffer>) => { info: SaveObligation };
  parseReserve: (pubkey: PublicKey, account: AccountInfo<Buffer>) => { info: SaveReserve };
};

type SaveObligation = {
  pubkey: PublicKey;
  deposits: SaveDeposit[];
  borrows: SaveBorrow[];
  depositedValue: { toString(): string };
  borrowedValue: { toString(): string };
  allowedBorrowValue: { toString(): string };
  unhealthyBorrowValue: { toString(): string };
  closeable: boolean;
};

type SaveDeposit = {
  depositReserve: PublicKey;
  depositedAmount: { toString(): string };
  marketValue: { toString(): string };
};

type SaveBorrow = {
  borrowReserve: PublicKey;
  borrowedAmountWads: { toString(): string };
  cumulativeBorrowRateWads: { toString(): string };
  marketValue: { toString(): string };
};

type SaveReserve = {
  liquidity: {
    mintPubkey: PublicKey;
    mintDecimals: number;
    marketPrice: { toString(): string };
  };
  config: {
    loanToValueRatio: number;
    liquidationThreshold: number;
    borrowWeight: string;
  };
};
