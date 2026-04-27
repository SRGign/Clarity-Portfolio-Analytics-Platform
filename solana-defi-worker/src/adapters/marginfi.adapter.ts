import { PublicKey, type Transaction, type VersionedTransaction } from "@solana/web3.js";
import { utils } from "@coral-xyz/anchor";
import { loadBankMetadatas, loadStakedBankMetadatas, type BankMetadataMap, type Wallet } from "@mrgnlabs/mrgn-common";
import { getConfig, MarginfiClient, MarginRequirementType } from "@mrgnlabs/marginfi-client-v2";
import type { DeFiAdapter, Position } from "../types.js";
import { connection, withRpcRetry } from "../rpc.js";
import { makePosition, pricedToken, runAdapterSafely, shortMint } from "./utils.js";

const MARGINFI_ACCOUNT_DISCRIMINATOR = Uint8Array.from([67, 178, 130, 109, 126, 114, 28, 42]);
const MARGINFI_ACCOUNT_BALANCES_OFFSET = 8 + 32 + 32;
const MARGINFI_BALANCE_SIZE = 104;
const MARGINFI_BALANCE_COUNT = 16;
const MARGINFI_BALANCE_ACTIVE_OFFSET = 0;
const MARGINFI_BALANCE_BANK_OFFSET = 1;

export class MarginfiAdapter implements DeFiAdapter {
  protocolId = "marginfi" as const;
  protocolName = "marginfi" as const;
  category = "lending" as const;

  async fetchPositions(walletAddress: string): Promise<Position[]> {
    return runAdapterSafely(this, walletAddress, async () => {
      const authority = new PublicKey(walletAddress);
      const config = getConfig("production");
      const accountAddresses = await findMarginfiAccountAddresses(config.programId, config.groupPk, authority);
      if (accountAddresses.length === 0) {
        return [];
      }

      const preloadedBankAddresses = await findActiveBankAddresses(accountAddresses);
      if (preloadedBankAddresses.length === 0) {
        return [];
      }

      const bankMetadataMap = await loadMarginfiBankMetadata();
      const client = await withRpcRetry(() =>
        withSingleRpcBatchFallback(() =>
          MarginfiClient.fetch(config, createReadOnlyWallet(authority), connection, {
            readOnly: true,
            bankMetadataMap,
            preloadedBankAddresses
          })
        )
      );
      const accounts = await withRpcRetry(() => client.getMultipleMarginfiAccounts(accountAddresses));
      const positions: Position[] = [];

      for (const account of accounts) {
        const accountAddress = account.address.toBase58();
        const health = account.computeHealthComponents(MarginRequirementType.Maintenance);

        for (const balance of account.activeBalances) {
          const bank = client.getBankByPk(balance.bankPk);
          const oraclePrice = client.getOraclePriceByBank(balance.bankPk);
          if (!bank || !oraclePrice) {
            continue;
          }

          const quantities = balance.computeQuantityUi(bank);
          const usdValues = balance.computeUsdValue(bank, oraclePrice, MarginRequirementType.Equity);
          const mint = bank.mint.toBase58();
          const symbol = bank.tokenSymbol ?? shortMint(mint);

          if (quantities.assets.gt(0)) {
            const amount = quantities.assets.toNumber();
            const valueUsd = usdValues.assets.toNumber();
            positions.push(
              makePosition({
                protocolId: this.protocolId,
                protocolName: this.protocolName,
                category: this.category,
                positionType: "deposit",
                positionId: `${accountAddress}:deposit:${bank.address.toBase58()}`,
                tokens: [
                  await pricedToken({
                    mint,
                    symbol,
                    decimals: bank.mintDecimals,
                    amount,
                    priceUsd: amount ? valueUsd / amount : bank.mintPrice
                  })
                ],
                metadata: {
                  accountAddress,
                  bankAddress: bank.address.toBase58(),
                  healthAssets: health.assets.toString(),
                  healthLiabilities: health.liabilities.toString(),
                  emissionsOutstanding: balance.emissionsOutstanding.toString()
                }
              })
            );
          }

          if (quantities.liabilities.gt(0)) {
            const amount = quantities.liabilities.toNumber();
            const valueUsd = usdValues.liabilities.toNumber();
            positions.push(
              makePosition({
                protocolId: this.protocolId,
                protocolName: this.protocolName,
                category: this.category,
                positionType: "borrow",
                positionId: `${accountAddress}:borrow:${bank.address.toBase58()}`,
                tokens: [
                  {
                    ...(await pricedToken({
                      mint,
                      symbol,
                      decimals: bank.mintDecimals,
                      amount: -amount,
                      priceUsd: amount ? valueUsd / amount : bank.mintPrice
                    })),
                    valueUsd: -Math.abs(valueUsd)
                  }
                ],
                metadata: {
                  accountAddress,
                  bankAddress: bank.address.toBase58(),
                  healthAssets: health.assets.toString(),
                  healthLiabilities: health.liabilities.toString(),
                  emissionsOutstanding: balance.emissionsOutstanding.toString()
                }
              })
            );
          }
        }
      }

      return positions;
    });
  }
}

async function findMarginfiAccountAddresses(programId: PublicKey, groupPk: PublicKey, authority: PublicKey): Promise<PublicKey[]> {
  const accounts = await withRpcRetry(() =>
    connection.getProgramAccounts(programId, {
      dataSlice: { offset: 0, length: 0 },
      filters: [
        {
          memcmp: {
            offset: 0,
            bytes: utils.bytes.bs58.encode(MARGINFI_ACCOUNT_DISCRIMINATOR)
          }
        },
        { memcmp: { offset: 8, bytes: groupPk.toBase58() } },
        { memcmp: { offset: 8 + 32, bytes: authority.toBase58() } }
      ]
    })
  );

  return accounts.map((account) => account.pubkey);
}

async function findActiveBankAddresses(accountAddresses: PublicKey[]): Promise<PublicKey[]> {
  const accountInfos = await withRpcRetry(() => connection.getMultipleAccountsInfo(accountAddresses));
  const banksByAddress = new Map<string, PublicKey>();

  for (let index = 0; index < accountInfos.length; index += 1) {
    const accountInfo = accountInfos[index];
    const accountAddress = accountAddresses[index];
    if (!accountInfo || !accountAddress) {
      continue;
    }

    for (const bankPk of readActiveBalanceBanks(accountInfo.data)) {
      banksByAddress.set(bankPk.toBase58(), bankPk);
    }
  }

  return [...banksByAddress.values()];
}

function readActiveBalanceBanks(data: Buffer): PublicKey[] {
  const banks: PublicKey[] = [];

  for (let index = 0; index < MARGINFI_BALANCE_COUNT; index += 1) {
    const offset = MARGINFI_ACCOUNT_BALANCES_OFFSET + index * MARGINFI_BALANCE_SIZE;
    const active = data[offset + MARGINFI_BALANCE_ACTIVE_OFFSET] === 1;
    if (!active) {
      continue;
    }

    const bankBytes = data.subarray(offset + MARGINFI_BALANCE_BANK_OFFSET, offset + MARGINFI_BALANCE_BANK_OFFSET + 32);
    const bankPk = new PublicKey(bankBytes);
    if (!bankPk.equals(PublicKey.default)) {
      banks.push(bankPk);
    }
  }

  return banks;
}

async function loadMarginfiBankMetadata(): Promise<BankMetadataMap> {
  return {
    ...(await loadBankMetadatas()),
    ...(await loadStakedBankMetadatas())
  };
}

async function withSingleRpcBatchFallback<T>(operation: () => Promise<T>): Promise<T> {
  type RpcRequest = { methodName: string; args: unknown[] };
  type RpcConnectionInternals = {
    _rpcBatchRequest?: (requests: RpcRequest[]) => Promise<unknown[]>;
    _rpcRequest?: (methodName: string, args: unknown[]) => Promise<unknown>;
  };
  const rpcConnection = connection as unknown as RpcConnectionInternals;
  const originalBatchRequest = rpcConnection._rpcBatchRequest;

  if (!rpcConnection._rpcRequest || !originalBatchRequest) {
    return operation();
  }

  rpcConnection._rpcBatchRequest = async (requests: RpcRequest[]) =>
    Promise.all(requests.map((request) => rpcConnection._rpcRequest?.(request.methodName, request.args)));

  try {
    return await operation();
  } finally {
    rpcConnection._rpcBatchRequest = originalBatchRequest;
  }
}

function createReadOnlyWallet(publicKey: PublicKey): Wallet {
  return {
    publicKey,
    signTransaction: async <T extends Transaction | VersionedTransaction>(transaction: T): Promise<T> => transaction,
    signAllTransactions: async <T extends Transaction | VersionedTransaction>(transactions: T[]): Promise<T[]> => transactions
  };
}
