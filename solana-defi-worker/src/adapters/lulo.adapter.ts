import type { DeFiAdapter, Position } from "../types.js";
import { logger } from "../logger.js";
import { runAdapterSafely } from "./utils.js";

export class LuloAdapter implements DeFiAdapter {
  protocolId = "lulo" as const;
  protocolName = "Lulo" as const;
  category = "yield" as const;

  async fetchPositions(walletAddress: string): Promise<Position[]> {
    return runAdapterSafely(this, walletAddress, async () => {
      const apiKey = process.env.LULO_API_KEY?.trim();
      logger.warn(
        {
          protocolId: this.protocolId,
          walletAddress,
          hasApiKey: Boolean(apiKey)
        },
        "Lulo account balance and earnings API requires developer-dashboard documentation/API access; adapter deferred"
      );

      return [];
    });
  }
}
