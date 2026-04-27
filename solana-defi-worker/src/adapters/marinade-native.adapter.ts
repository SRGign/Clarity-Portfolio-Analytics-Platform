import { PublicKey } from "@solana/web3.js";
import type { DeFiAdapter, Position, PositionToken } from "../types.js";
import { connection, withRpcRetry } from "../rpc.js";
import { logger } from "../logger.js";
import { makePosition, pricedToken, readNumber, readString, runAdapterSafely } from "./utils.js";

const SOL_MINT = "So11111111111111111111111111111111111111112";

export class MarinadeNativeAdapter implements DeFiAdapter {
  protocolId = "marinade-native" as const;
  protocolName = "Marinade Native" as const;
  category = "staking" as const;

  async fetchPositions(walletAddress: string): Promise<Position[]> {
    return runAdapterSafely(this, walletAddress, async () => {
      const sdkModule = await import("@marinade.finance/native-staking-sdk");
      const user = new PublicKey(walletAddress);
      const sdk = createMarinadeSdk(sdkModule, connection);
      if (!sdk?.getStakeAccounts) {
        logger.warn({ protocolId: this.protocolId }, "Marinade native SDK does not expose getStakeAccounts");
        return [];
      }

      const getStakeAccounts = sdk.getStakeAccounts;
      const accountsResponse = await withRpcRetry(() => getStakeAccounts(user));
      const accounts = extractStakeAccounts(accountsResponse);
      const fetchRewards = sdk.fetchRewards;
      const rewards = fetchRewards ? await withRpcRetry(() => fetchRewards(user)).catch(() => []) : [];
      const positions: Position[] = [];

      for (const account of accounts ?? []) {
        const lamports = readStakeLamports(account);
        const token = await pricedToken({
          mint: SOL_MINT,
          symbol: "SOL",
          decimals: 9,
          rawAmount: lamports
        });
        const pendingRewards = await this.rewardsForAccount(account, rewards);

        positions.push(
          makePosition({
            protocolId: this.protocolId,
            protocolName: this.protocolName,
            category: this.category,
            positionType: "staked",
            positionId: readString(account, ["stakeAccount", "stakeAccountAddress", "publicKey", "pubkey"], `${walletAddress}:stake:${positions.length}`),
            tokens: [token],
            pendingRewards,
            metadata: {
              validatorVoteAccount: readValidatorVoteAccount(account),
              state: readStakeState(account)
            }
          })
        );
      }

      return positions;
    });
  }

  private async rewardsForAccount(account: unknown, rewards: unknown): Promise<PositionToken[]> {
    const accountId = readString(account, ["stakeAccount", "stakeAccountAddress", "publicKey", "pubkey"]);
    const records = extractRewardRecords(rewards);
    const reward = records.find((record) => readString(record, ["stakeAccount", "stakeAccountAddress", "publicKey", "pubkey"]) === accountId);
    const lamports = readNumber(reward, ["lamports", "rewardLamports", "amount"], 0);

    if (!lamports) {
      return [];
    }

    return [await pricedToken({ mint: SOL_MINT, symbol: "SOL", decimals: 9, rawAmount: lamports })];
  }
}

type MarinadeSdk = {
  getStakeAccounts?: (owner: PublicKey) => Promise<unknown>;
  fetchRewards?: (owner: PublicKey) => Promise<unknown>;
};

function extractStakeAccounts(response: unknown): unknown[] {
  if (Array.isArray(response)) {
    return response;
  }

  const object = response as Record<string, unknown> | undefined;
  const candidates = [
    object?.staking,
    object?.all,
    object?.stakeAccounts,
    object?.accounts,
    (object?.value as Record<string, unknown> | undefined)?.accounts,
    object?.data
  ];

  for (const candidate of candidates) {
    if (Array.isArray(candidate)) {
      return candidate;
    }
  }

  return [];
}

function extractRewardRecords(response: unknown): unknown[] {
  if (Array.isArray(response)) {
    return response;
  }

  const object = response as Record<string, unknown> | undefined;
  const candidates = [
    object?.rewards,
    object?.data,
    object?.data_points,
    (object?.value as Record<string, unknown> | undefined)?.rewards
  ];

  for (const candidate of candidates) {
    if (Array.isArray(candidate)) {
      return candidate;
    }
  }

  return object ? Object.values(object) : [];
}

function readStakeLamports(account: unknown): number {
  const direct = readNumber(account, ["lamports", "activeStakeLamports", "stakeLamports", "amount"], Number.NaN);
  if (Number.isFinite(direct)) {
    return direct;
  }

  const nested = (account as { account?: unknown } | undefined)?.account;
  const accountLamports = readNumber(nested, ["lamports"], Number.NaN);
  if (Number.isFinite(accountLamports)) {
    return accountLamports;
  }

  const delegation = readParsedDelegation(account);
  return readNumber(delegation, ["stake"], 0);
}

function readValidatorVoteAccount(account: unknown): string {
  const direct = readString(account, ["validatorVoteAccount", "voteAccount", "voter"]);
  if (direct) {
    return direct;
  }

  return readString(readParsedDelegation(account), ["voter"]);
}

function readStakeState(account: unknown): string {
  const direct = readString(account, ["state", "status"]);
  if (direct) {
    return direct;
  }

  const parsedInfo = readParsedInfo(account);
  return readString(parsedInfo, ["state"], "unknown");
}

function readParsedDelegation(account: unknown): unknown {
  const parsedInfo = readParsedInfo(account) as Record<string, unknown> | undefined;
  const stake = parsedInfo?.stake as Record<string, unknown> | undefined;
  return stake?.delegation;
}

function readParsedInfo(account: unknown): unknown {
  const nestedAccount = (account as { account?: unknown } | undefined)?.account as Record<string, unknown> | undefined;
  const data = nestedAccount?.data as Record<string, unknown> | undefined;
  const parsed = data?.parsed as Record<string, unknown> | undefined;
  return parsed?.info;
}

function createMarinadeSdk(module: Record<string, unknown>, rpcConnection: unknown): MarinadeSdk | undefined {
  const Constructor = module.MarinadeNativeStakingSdk ?? module.NativeStakingSDK ?? module.default;
  if (typeof Constructor === "function") {
    const Ctor = Constructor as new (...args: unknown[]) => MarinadeSdk;
    return new Ctor({ connection: rpcConnection });
  }
  if (typeof module.createMarinadeNativeStakingSdk === "function") {
    return module.createMarinadeNativeStakingSdk({ connection: rpcConnection }) as MarinadeSdk;
  }
  return undefined;
}
