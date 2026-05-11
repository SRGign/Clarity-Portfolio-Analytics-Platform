import { describe, expect, it } from "vitest";
import { listProtocols } from "../src/service.js";
import { isValidPosition, makePosition, pricedToken } from "../src/adapters/utils.js";

describe("worker shape contracts", () => {
  it("registers all requested protocol adapters", () => {
    expect(listProtocols()).toEqual([
      { protocolId: "jupiter-lend", protocolName: "Jupiter Lend", category: "lending" },
      { protocolId: "kamino-lend", protocolName: "Kamino Lend", category: "lending" },
      { protocolId: "kamino-vault", protocolName: "Kamino Vault", category: "yield" },
      { protocolId: "kamino-liquidity", protocolName: "Kamino Liquidity", category: "yield" },
      { protocolId: "loopscale", protocolName: "Loopscale", category: "lending" },
      { protocolId: "lulo", protocolName: "Lulo", category: "yield" },
      { protocolId: "marinade-native", protocolName: "Marinade Native", category: "staking" },
      { protocolId: "raydium-clmm", protocolName: "Raydium CLMM", category: "clmm" },
      { protocolId: "raydium-amm", protocolName: "Raydium AMM", category: "amm" },
      { protocolId: "orca-whirlpool", protocolName: "Orca Whirlpool", category: "clmm" },
      { protocolId: "meteora-dlmm", protocolName: "Meteora DLMM", category: "clmm" },
      { protocolId: "meteora-dynamic", protocolName: "Meteora Dynamic AMM", category: "amm" },
      { protocolId: "marginfi", protocolName: "marginfi", category: "lending" },
      { protocolId: "save", protocolName: "Save Protocol", category: "lending" },
      { protocolId: "jupiter-perps", protocolName: "Jupiter Perps", category: "perps" }
    ]);
  });

  it("validates normalized position shape", async () => {
    const token = await pricedToken({
      mint: "So11111111111111111111111111111111111111112",
      symbol: "SOL",
      decimals: 9,
      amount: 1,
      priceUsd: 100
    });
    const position = makePosition({
      protocolId: "test",
      protocolName: "Test",
      category: "staking",
      positionType: "staked",
      positionId: "position-1",
      tokens: [token]
    });

    expect(isValidPosition(position)).toBe(true);
    expect(position.totalValueUsd).toBe(100);
  });
});
