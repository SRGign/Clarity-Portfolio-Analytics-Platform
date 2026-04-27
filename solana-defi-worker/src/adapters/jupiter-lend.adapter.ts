import type { DeFiAdapter, Position, PositionToken } from "../types.js";
import { makePosition, pricedToken, readNumber, readString, runAdapterSafely } from "./utils.js";

const JUPITER_LEND_API_BASE = "https://lite-api.jup.ag/lend/v1";

type JupiterLendPosition = {
  token?: {
    address?: string;
    symbol?: string;
    uiSymbol?: string;
    decimals?: number;
    assetAddress?: string;
    asset?: {
      address?: string;
      symbol?: string;
      uiSymbol?: string;
      decimals?: number;
      price?: string;
    };
    supplyRate?: string;
    totalRate?: string;
  };
  ownerAddress?: string;
  shares?: string;
  underlyingAssets?: string;
  underlyingBalance?: string;
  allowance?: string;
};

type JupiterLendEarning = {
  address?: string;
  earnings?: string;
  totalDeposits?: string;
  totalWithdraws?: string;
  totalBalance?: string;
  totalAssets?: string;
};

export class JupiterLendAdapter implements DeFiAdapter {
  protocolId = "jupiter-lend" as const;
  protocolName = "Jupiter Lend" as const;
  category = "lending" as const;

  async fetchPositions(walletAddress: string): Promise<Position[]> {
    return runAdapterSafely(this, walletAddress, async () => {
      const apiPositions = await fetchPositions(walletAddress);
      const activePositions = apiPositions.filter(hasBalance);
      const earnings = await fetchEarnings(walletAddress, activePositions);
      const earningsByAddress = new Map(earnings.map((earning) => [earning.address, earning]));
      const positions: Position[] = [];

      for (const apiPosition of activePositions) {
        const token = apiPosition.token;
        const asset = token?.asset;
        const mint = asset?.address ?? token?.assetAddress ?? token?.address;
        if (!mint) {
          continue;
        }

        const priceUsd = readNumber(asset, ["price"], 0);
        const positionToken = await pricedToken({
          mint,
          symbol: asset?.uiSymbol ?? asset?.symbol ?? token?.uiSymbol ?? token?.symbol,
          decimals: asset?.decimals ?? token?.decimals ?? 0,
          rawAmount: readRawPositionAmount(apiPosition),
          priceUsd
        });
        const earning = earningsByAddress.get(token?.address);
        const pendingRewards = await pendingRewardsForPosition(mint, positionToken, earning);

        positions.push(
          makePosition({
            protocolId: this.protocolId,
            protocolName: this.protocolName,
            category: this.category,
            positionType: "deposit",
            positionId: token?.address ?? `${walletAddress}:${mint}`,
            tokens: [positionToken],
            pendingRewards,
            metadata: {
              ownerAddress: apiPosition.ownerAddress,
              marketAddress: token?.address,
              sharesAmount: readString(apiPosition, ["shares"], "0"),
              underlyingAssets: readString(apiPosition, ["underlyingAssets"], "0"),
              allowance: readString(apiPosition, ["allowance"], "0"),
              apy: readNumber(token, ["totalRate"], 0) / 100,
              supplyRate: readNumber(token, ["supplyRate"], 0) / 100,
              rewardsRate: readNumber(token, ["rewardsRate"], 0) / 100,
              totalDeposits: readString(earning, ["totalDeposits"], "0"),
              totalWithdraws: readString(earning, ["totalWithdraws"], "0")
            }
          })
        );
      }

      return positions;
    });
  }
}

async function fetchPositions(walletAddress: string): Promise<JupiterLendPosition[]> {
  const response = await fetch(`${JUPITER_LEND_API_BASE}/earn/positions?users=${encodeURIComponent(walletAddress)}`, {
    headers: jupiterHeaders()
  });
  if (!response.ok) {
    throw new Error(`Jupiter Lend positions API failed: ${response.status} ${response.statusText}`);
  }

  const body = (await response.json()) as unknown;
  return Array.isArray(body) ? (body as JupiterLendPosition[]) : [];
}

async function fetchEarnings(walletAddress: string, positions: JupiterLendPosition[]): Promise<JupiterLendEarning[]> {
  const positionAddresses = positions.map((position) => position.token?.address).filter((address): address is string => Boolean(address));
  if (positionAddresses.length === 0) {
    return [];
  }

  const url =
    `${JUPITER_LEND_API_BASE}/earn/earnings?user=${encodeURIComponent(walletAddress)}` +
    `&positions=${encodeURIComponent(positionAddresses.join(","))}`;
  const response = await fetch(url, { headers: jupiterHeaders() });
  if (!response.ok) {
    return [];
  }

  const body = (await response.json()) as unknown;
  return Array.isArray(body) ? (body as JupiterLendEarning[]) : [];
}

function jupiterHeaders(): Record<string, string> {
  const apiKey = process.env.JUPITER_API_KEY?.trim();
  return apiKey ? { "x-api-key": apiKey, Authorization: `Bearer ${apiKey}` } : {};
}

function hasBalance(position: JupiterLendPosition): boolean {
  return readNumber(position, ["shares"], 0) > 0 || readNumber(position, ["underlyingAssets"], 0) > 0;
}

function readRawPositionAmount(position: JupiterLendPosition): string {
  for (const key of ["underlyingBalance", "underlyingAssets", "shares"]) {
    const amount = readNumber(position, [key], 0);
    if (amount > 0) {
      return readString(position, [key], "0");
    }
  }

  return "0";
}

async function pendingRewardsForPosition(
  mint: string,
  token: PositionToken,
  earning: JupiterLendEarning | undefined
): Promise<PositionToken[]> {
  const rawEarnings = readNumber(earning, ["earnings"], 0);
  if (!rawEarnings) {
    return [];
  }

  return [
    await pricedToken({
      mint,
      symbol: token.symbol,
      decimals: token.decimals,
      rawAmount: rawEarnings,
      priceUsd: token.priceUsd
    })
  ];
}
