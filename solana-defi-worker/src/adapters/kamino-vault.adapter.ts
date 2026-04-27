import type { DeFiAdapter, Position } from "../types.js";
import { makePosition, pricedToken, readNumber, readString, runAdapterSafely } from "./utils.js";

const KAMINO_API_BASE = "https://api.kamino.finance";

type KaminoVaultPosition = {
  vaultAddress?: string;
  address?: string;
  stakedShares?: string;
  unstakedShares?: string;
  totalShares?: string;
};

type KaminoVault = {
  address?: string;
  state?: {
    name?: string;
    tokenMint?: string;
    tokenMintDecimals?: number;
    sharesMint?: string;
    sharesMintDecimals?: number;
    prevAum?: string;
    sharesIssued?: string;
    cumulativeEarnedInterest?: string;
  };
};

type KaminoVaultMetric = {
  createdOn?: string;
  sharesAmount?: string;
  usdAmount?: string;
  solAmount?: string;
  apy?: string;
  cumulativeInterestEarned?: string;
  cumulativeInterestEarnedUsd?: string;
};

let cachedVaults: Map<string, KaminoVault> | undefined;

export class KaminoVaultAdapter implements DeFiAdapter {
  protocolId = "kamino-vault" as const;
  protocolName = "Kamino Vault" as const;
  category = "yield" as const;

  async fetchPositions(walletAddress: string): Promise<Position[]> {
    return runAdapterSafely(this, walletAddress, async () => {
      const vaultPositions = await this.fetchVaultPositions(walletAddress);
      const vaults = await getVaultsByAddress();
      const positions: Position[] = [];

      for (const vaultPosition of vaultPositions) {
        const vaultAddress = vaultPosition.vaultAddress ?? vaultPosition.address;
        if (!vaultAddress) {
          continue;
        }

        const vault = vaults.get(vaultAddress);
        const state = vault?.state;
        const latestMetric = await this.fetchLatestMetric(walletAddress, vaultAddress);
        const mint = state?.tokenMint ?? readString(vaultPosition, ["tokenMint", "mint"]);
        if (!mint) {
          continue;
        }

        const totalShares = readNumber(vaultPosition, ["totalShares"], 0);
        const valueUsd = readNumber(latestMetric, ["usdAmount"], Number.NaN);
        const baseToken = await pricedToken({
          mint,
          symbol: readString(state, ["name"], "KVault"),
          decimals: state?.tokenMintDecimals ?? 0,
          amount: Number.isFinite(valueUsd) ? 0 : totalShares
        });
        const amount = Number.isFinite(valueUsd) && baseToken.priceUsd > 0 ? valueUsd / baseToken.priceUsd : baseToken.amount;

        positions.push(
          makePosition({
            protocolId: this.protocolId,
            protocolName: this.protocolName,
            category: this.category,
            positionType: "deposit",
            positionId: vaultAddress,
            tokens: [
              {
                ...baseToken,
                amount,
                valueUsd: Number.isFinite(valueUsd) ? valueUsd : amount * baseToken.priceUsd
              }
            ],
            metadata: {
              vaultAddress,
              vaultName: state?.name,
              totalShares,
              stakedShares: readNumber(vaultPosition, ["stakedShares"], 0),
              unstakedShares: readNumber(vaultPosition, ["unstakedShares"], 0),
              sharesMint: state?.sharesMint,
              sharesMintDecimals: state?.sharesMintDecimals,
              apy: readNumber(latestMetric, ["apy"], 0),
              solAmount: readNumber(latestMetric, ["solAmount"], 0),
              cumulativeInterestEarned: readNumber(latestMetric, ["cumulativeInterestEarned"], 0),
              cumulativeInterestEarnedUsd: readNumber(latestMetric, ["cumulativeInterestEarnedUsd"], 0)
            }
          })
        );
      }

      return positions;
    });
  }

  private async fetchVaultPositions(walletAddress: string): Promise<KaminoVaultPosition[]> {
    const response = await fetch(`${KAMINO_API_BASE}/kvaults/users/${walletAddress}/positions`);
    if (!response.ok) {
      throw new Error(`Kamino KVault positions API failed: ${response.status} ${response.statusText}`);
    }

    const body = (await response.json()) as unknown;
    return Array.isArray(body) ? (body as KaminoVaultPosition[]) : [];
  }

  private async fetchLatestMetric(walletAddress: string, vaultAddress: string): Promise<KaminoVaultMetric | undefined> {
    const response = await fetch(`${KAMINO_API_BASE}/kvaults/users/${walletAddress}/vaults/${vaultAddress}/metrics/history`);
    if (!response.ok) {
      return undefined;
    }

    const body = (await response.json()) as unknown;
    if (!Array.isArray(body) || body.length === 0) {
      return undefined;
    }

    return (body as KaminoVaultMetric[]).sort((a, b) => Date.parse(b.createdOn ?? "") - Date.parse(a.createdOn ?? ""))[0];
  }
}

async function getVaultsByAddress(): Promise<Map<string, KaminoVault>> {
  if (cachedVaults) {
    return cachedVaults;
  }

  const response = await fetch(`${KAMINO_API_BASE}/kvaults/vaults`);
  if (!response.ok) {
    cachedVaults = new Map();
    return cachedVaults;
  }

  const body = (await response.json()) as unknown;
  const vaults = Array.isArray(body) ? (body as KaminoVault[]) : [];
  const entries: Array<[string, KaminoVault]> = [];
  for (const vault of vaults) {
    if (vault.address) {
      entries.push([vault.address, vault]);
    }
  }
  cachedVaults = new Map(entries);
  return cachedVaults;
}
