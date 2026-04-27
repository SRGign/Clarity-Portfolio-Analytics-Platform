import { PublicKey } from "@solana/web3.js";
import type { DeFiAdapter, Position } from "../types.js";
import { connection, withRpcRetry } from "../rpc.js";
import { logger } from "../logger.js";
import { runAdapterSafely } from "./utils.js";

export class JupiterPerpsAdapter implements DeFiAdapter {
  protocolId = "jupiter-perps" as const;
  protocolName = "Jupiter Perps" as const;
  category = "perps" as const;

  async fetchPositions(walletAddress: string): Promise<Position[]> {
    return runAdapterSafely(this, walletAddress, async () => {
      const programId = process.env.JUPITER_PERPS_PROGRAM_ID;
      if (!programId) {
        logger.warn({ protocolId: this.protocolId }, "JUPITER_PERPS_PROGRAM_ID is not configured; skipping Jupiter Perps");
        return [];
      }

      const owner = new PublicKey(walletAddress);
      const accounts = await withRpcRetry(() =>
        connection.getProgramAccounts(new PublicKey(programId), {
          filters: [{ memcmp: { offset: 8, bytes: owner.toBase58() } }]
        })
      );

      if (accounts.length > 0) {
        logger.warn(
          { protocolId: this.protocolId, walletAddress, accountCount: accounts.length },
          "Jupiter Perps accounts found but IDL-specific binary parser needs protocol account layout confirmation"
        );
      }

      return [];
    });
  }
}
