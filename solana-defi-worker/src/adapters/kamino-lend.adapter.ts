import type { DeFiAdapter, Position, PositionToken } from "../types.js";
import { logger } from "../logger.js";
import { makePosition, pricedToken, readNumber, readString, runAdapterSafely } from "./utils.js";

const MAIN_MARKET = "7u3HeHxYDLhnCoErrtycNokbQYbWGzLs6JSDqGAv5PfF";
const KAMINO_API_BASE = "https://api.kamino.finance";

type KaminoMarketDescriptor = {
  name?: string;
  lendingMarket?: string;
};

let cachedMarkets: KaminoMarketDescriptor[] | undefined;

export class KaminoLendAdapter implements DeFiAdapter {
  protocolId = "kamino-lend" as const;
  protocolName = "Kamino Lend" as const;
  category = "lending" as const;

  async fetchPositions(walletAddress: string): Promise<Position[]> {
    return runAdapterSafely(this, walletAddress, async () => {
      const obligations = await this.fetchApiObligations(walletAddress);
      const positions: Position[] = [];

      for (const obligation of obligations) {
        if (!obligation) {
          continue;
        }

        const obligationRecord = obligation as Record<string, unknown>;
        const obligationId = readString(obligation, ["loanId", "obligationAddress", "pubkey", "address"], walletAddress);
        const deposits = await this.extractApiSide(obligation, "deposits");
        const borrows = await this.extractApiSide(obligation, "borrows");
        const metadata = {
          healthFactor: readNumber(obligationRecord.loanInfo ?? obligation, ["healthFactor"], 0),
          ltv: readNumber(obligationRecord.loanInfo ?? obligation, ["currentLtv", "loanToValue", "ltv", "loanToValueRatio"], 0),
          marketId: readString(obligation, ["marketId", "marketPubkey"]),
          leverage: readNumber(obligation, ["leverage"], 0),
          obligationId
        };

        for (const token of deposits) {
          positions.push(
            makePosition({
              protocolId: this.protocolId,
              protocolName: this.protocolName,
              category: this.category,
              positionType: "deposit",
              positionId: `${obligationId}:deposit:${token.mint}`,
              tokens: [token],
              metadata
            })
          );
        }

        for (const token of borrows) {
          positions.push(
            makePosition({
              protocolId: this.protocolId,
              protocolName: this.protocolName,
              category: this.category,
              positionType: "borrow",
              positionId: `${obligationId}:borrow:${token.mint}`,
              tokens: [{ ...token, amount: -Math.abs(token.amount), valueUsd: -Math.abs(token.valueUsd) }],
              metadata
            })
          );
        }
      }

      return positions;
    });
  }

  private async fetchApiObligations(walletAddress: string): Promise<unknown[]> {
    const markets = await getKaminoMarkets();
    const obligations: unknown[] = [];

    for (const market of markets) {
      if (!market.lendingMarket) {
        continue;
      }

      const url = `${KAMINO_API_BASE}/kamino-market/${market.lendingMarket}/users/${walletAddress}/obligations?env=mainnet-beta`;
      const response = await fetch(url);
      if (!response.ok) {
        throw new Error(`Kamino API failed for ${market.name ?? market.lendingMarket}: ${response.status} ${response.statusText}`);
      }

      const body = (await response.json()) as unknown;
      const marketObligations = Array.isArray(body) ? body : body ? [body] : [];
      obligations.push(
        ...marketObligations.map((obligation) => ({
          ...(typeof obligation === "object" && obligation ? (obligation as Record<string, unknown>) : { value: obligation }),
          marketId: readString(obligation, ["marketId"], market.lendingMarket),
          marketName: market.name
        }))
      );
    }

    return obligations;
  }

  private async extractApiSide(obligation: unknown, side: "deposits" | "borrows"): Promise<PositionToken[]> {
    const obligationRecord = obligation as Record<string, unknown>;
    const loanInfo = obligationRecord.loanInfo as Record<string, unknown> | undefined;
    const sideContainer =
      side === "deposits"
        ? ((loanInfo?.collateral as Record<string, unknown> | undefined)?.deposits ?? obligationRecord.deposits)
        : ((loanInfo?.debt as Record<string, unknown> | undefined)?.borrows ?? obligationRecord.borrows);
    const entries = Array.isArray(sideContainer) ? sideContainer : [];
    const tokens: PositionToken[] = [];

    for (const balance of entries) {
      const mint = readString(balance, ["tokenMint", "mint", "liquidityMint", "mintAddress"]);
      if (!mint) {
        logger.warn({ protocolId: this.protocolId, side }, "Kamino API token mint missing");
        continue;
      }

      const amount = readNumber(balance, ["tokenAmount", "amount"], 0);
      const token = await pricedToken({
        mint,
        symbol: readString(balance, ["tokenName", "symbol", "tokenSymbol"], undefinedSymbol(mint)),
        decimals: readNumber(balance, ["decimals", "mintDecimals", "tokenDecimals"], 0),
        amount,
        priceUsd: readNumber(balance, ["tokenPrice", "priceUsd"], Number.NaN)
      });
      const valueUsd = readNumber(balance, ["tokenValue", "valueUsd", "marketValueRefreshed"], Number.NaN);
      tokens.push(Number.isFinite(valueUsd) ? { ...token, priceUsd: amount ? valueUsd / amount : 0, valueUsd } : token);
    }

    return tokens;
  }
}

function undefinedSymbol(mint: string): string {
  return mint.length > 8 ? `${mint.slice(0, 4)}...${mint.slice(-4)}` : mint;
}

async function getKaminoMarkets(): Promise<KaminoMarketDescriptor[]> {
  if (cachedMarkets) {
    return cachedMarkets;
  }

  const response = await fetch(`${KAMINO_API_BASE}/v2/kamino-market?env=mainnet-beta`);
  if (!response.ok) {
    return [{ name: "Main Market", lendingMarket: MAIN_MARKET }];
  }

  const body = (await response.json()) as unknown;
  cachedMarkets = Array.isArray(body) ? (body as KaminoMarketDescriptor[]) : [{ name: "Main Market", lendingMarket: MAIN_MARKET }];
  return cachedMarkets;
}
