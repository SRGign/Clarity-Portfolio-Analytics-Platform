import { PublicKey } from "@solana/web3.js";
import { getMint } from "@solana/spl-token";
import type { DeFiAdapter, Position } from "../types.js";
import { connection, withRpcRetry } from "../rpc.js";
import { logger } from "../logger.js";
import { makePosition, pricedToken, readNumber, readString, runAdapterSafely, shortMint } from "./utils.js";

const LOOPSCALE_API_BASE = "https://tars.loopscale.com/v1";
const ACTIVE_LOAN_FILTER = 0;
const PAGE_SIZE = 100;

type LoopscaleLoanInfoResponse = {
  loanInfos?: LoopscaleLoanInfo[];
  totalCount?: number;
};

type LoopscaleLoanInfo = {
  loan?: {
    address?: string;
    borrower?: string;
    loanStatus?: number;
    startTime?: number;
  };
  loanType?: number;
  ledgers?: LoopscaleLedger[];
  collateral?: LoopscaleCollateral[];
  events?: LoopscaleEvent[];
  collateralYield?: LoopscaleCollateralYield[];
};

type LoopscaleLedger = {
  ledgerIndex?: number;
  status?: number;
  strategy?: string;
  principalMint?: string;
  marketInformation?: string;
  apy?: number;
  principalDue?: number;
  principalRepaid?: number;
  interestOutstanding?: number;
  duration?: number;
  startTime?: number;
  endTime?: number;
};

type LoopscaleCollateral = {
  assetType?: number;
  assetIdentifier?: string;
  assetMint?: string;
  amount?: number;
};

type LoopscaleEvent = {
  originalLender?: string;
  newLender?: string;
  usdPrice?: number;
  action?: number;
};

type LoopscaleCollateralYield = {
  collateralMint?: string;
  collateralApy?: number;
};

const mintDecimalsCache = new Map<string, number>();

export class LoopscaleAdapter implements DeFiAdapter {
  protocolId = "loopscale" as const;
  protocolName = "Loopscale" as const;
  category = "lending" as const;

  async fetchPositions(walletAddress: string): Promise<Position[]> {
    return runAdapterSafely(this, walletAddress, async () => {
      const borrowerLoans = await fetchLoanInfos({ borrowers: [walletAddress] });
      const lenderLoans = await fetchLoanInfos({ lenders: [walletAddress] });

      const positions: Position[] = [];
      for (const loanInfo of borrowerLoans) {
        positions.push(...(await this.normalizeLoan(loanInfo, "borrow")));
      }
      for (const loanInfo of lenderLoans) {
        positions.push(...(await this.normalizeLoan(loanInfo, "deposit")));
      }

      return dedupePositions(positions);
    });
  }

  private async normalizeLoan(loanInfo: LoopscaleLoanInfo, side: "borrow" | "deposit"): Promise<Position[]> {
    const positions: Position[] = [];
    const loanAddress = loanInfo.loan?.address;
    const ledgers = Array.isArray(loanInfo.ledgers) ? loanInfo.ledgers : [];

    for (const ledger of ledgers) {
      const mint = ledger.principalMint;
      if (!loanAddress || !mint) {
        continue;
      }

      const decimals = await getMintDecimals(mint);
      const rawPrincipal = readNumber(ledger, ["principalDue"], 0);
      const amount = decimals > 0 ? rawPrincipal / 10 ** decimals : rawPrincipal;
      const signedAmount = side === "borrow" ? -Math.abs(amount) : amount;
      const eventPrice = latestUsdPrice(loanInfo.events);
      const token = await pricedToken({
        mint,
        symbol: shortMint(mint),
        decimals,
        amount: signedAmount,
        priceUsd: eventPrice
      });

      positions.push(
        makePosition({
          protocolId: this.protocolId,
          protocolName: this.protocolName,
          category: this.category,
          positionType: side,
          positionId: `${loanAddress}:${side}:${ledger.ledgerIndex ?? mint}`,
          tokens: [side === "borrow" ? { ...token, valueUsd: -Math.abs(token.valueUsd) } : token],
          metadata: {
            loanAddress,
            borrower: loanInfo.loan?.borrower,
            loanStatus: loanInfo.loan?.loanStatus,
            loanType: loanInfo.loanType,
            ledgerIndex: ledger.ledgerIndex,
            orderId: ledger.strategy,
            fixedRate: ledger.apy,
            expirationDate: ledger.endTime ? new Date(ledger.endTime * 1000).toISOString() : undefined,
            interestOutstanding: ledger.interestOutstanding,
            principalRepaid: ledger.principalRepaid,
            marketInformation: ledger.marketInformation,
            collateral: loanInfo.collateral ?? [],
            collateralYield: loanInfo.collateralYield ?? []
          }
        })
      );
    }

    return positions;
  }
}

async function fetchLoanInfos(filters: { borrowers?: string[]; lenders?: string[] }): Promise<LoopscaleLoanInfo[]> {
  const response = await fetch(`${LOOPSCALE_API_BASE}/markets/loans/info`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      ...filters,
      filterType: ACTIVE_LOAN_FILTER,
      page: 0,
      pageSize: PAGE_SIZE
    })
  });
  if (!response.ok) {
    if (response.status === 429) {
      logger.warn({ protocolId: "loopscale" }, "Loopscale loan info API rate limited; returning empty positions");
      return [];
    }

    throw new Error(`Loopscale loan info API failed: ${response.status} ${response.statusText}`);
  }

  const body = (await response.json()) as unknown;
  if (Array.isArray(body)) {
    return body.flatMap((entry) => ((entry as LoopscaleLoanInfoResponse | undefined)?.loanInfos ?? []));
  }

  return (body as LoopscaleLoanInfoResponse | undefined)?.loanInfos ?? [];
}

async function getMintDecimals(mint: string): Promise<number> {
  const cached = mintDecimalsCache.get(mint);
  if (cached !== undefined) {
    return cached;
  }

  try {
    const mintAccount = await withRpcRetry(() => getMint(connection, new PublicKey(mint)));
    mintDecimalsCache.set(mint, mintAccount.decimals);
    return mintAccount.decimals;
  } catch {
    mintDecimalsCache.set(mint, 0);
    return 0;
  }
}

function latestUsdPrice(events: LoopscaleEvent[] | undefined): number | undefined {
  if (!events) {
    return undefined;
  }

  for (let index = events.length - 1; index >= 0; index -= 1) {
    const event = events[index];
    if (event && Number.isFinite(event.usdPrice)) {
      return event.usdPrice;
    }
  }

  return undefined;
}

function dedupePositions(positions: Position[]): Position[] {
  const seen = new Set<string>();
  return positions.filter((position) => {
    if (seen.has(position.positionId)) {
      return false;
    }
    seen.add(position.positionId);
    return true;
  });
}
