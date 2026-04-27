import { describe, expect, it } from "vitest";
import type { DeFiAdapter, Position } from "../src/types.js";
import { JupiterLendAdapter } from "../src/adapters/jupiter-lend.adapter.js";
import { KaminoLendAdapter } from "../src/adapters/kamino-lend.adapter.js";
import { KaminoLiquidityAdapter } from "../src/adapters/kamino-liquidity.adapter.js";
import { KaminoVaultAdapter } from "../src/adapters/kamino-vault.adapter.js";
import { LoopscaleAdapter } from "../src/adapters/loopscale.adapter.js";
import { LuloAdapter } from "../src/adapters/lulo.adapter.js";
import { MarginfiAdapter } from "../src/adapters/marginfi.adapter.js";
import { MarinadeNativeAdapter } from "../src/adapters/marinade-native.adapter.js";
import { MeteoraDlmmAdapter } from "../src/adapters/meteora-dlmm.adapter.js";
import { RaydiumAmmAdapter } from "../src/adapters/raydium-amm.adapter.js";
import { RaydiumClmmAdapter } from "../src/adapters/raydium-clmm.adapter.js";
import { SaveAdapter } from "../src/adapters/save.adapter.js";

const shouldRun =
  process.env.RUN_MAINNET_SMOKE === "true" &&
  Boolean(process.env.SOLANA_RPC_URL || process.env.RPC_FAST_URL || process.env.HELIUS_RPC_URL) &&
  Boolean(process.env.TEST_WALLET?.trim() || process.env.MAINNET_SMOKE_WALLET?.trim());
const testWallet = process.env.TEST_WALLET?.trim() || process.env.MAINNET_SMOKE_WALLET?.trim() || "";
const maybeDescribe = shouldRun ? describe : describe.skip;

const adapters: DeFiAdapter[] = [
  new JupiterLendAdapter(),
  new KaminoLendAdapter(),
  new KaminoLiquidityAdapter(),
  new KaminoVaultAdapter(),
  new LoopscaleAdapter(),
  new LuloAdapter(),
  new MarginfiAdapter(),
  new MarinadeNativeAdapter(),
  new MeteoraDlmmAdapter(),
  new RaydiumClmmAdapter(),
  new RaydiumAmmAdapter(),
  new SaveAdapter()
];

maybeDescribe("mainnet adapter smoke tests", () => {
  for (const adapter of adapters) {
    it(`${adapter.protocolId} returns conforming positions or an empty array`, async () => {
      const positions = await adapter.fetchPositions(testWallet);
      expect(Array.isArray(positions)).toBe(true);
      positions.forEach(expectPositionShape);
      printPositions(adapter.protocolId, positions);
    }, 120_000);
  }
});

function expectPositionShape(position: Position): void {
  expect(position.protocolId).toBeTruthy();
  expect(position.protocolName).toBeTruthy();
  expect(position.category).toBeTruthy();
  expect(position.positionType).toBeTruthy();
  expect(position.positionId).toBeTruthy();
  expect(Array.isArray(position.tokens)).toBe(true);
  expect(typeof position.totalValueUsd).toBe("number");
}

function printPositions(protocolId: string, positions: Position[]): void {
  const totalUsd = positions.reduce((sum, position) => sum + position.totalValueUsd, 0);
  console.log(
    JSON.stringify(
      {
        protocolId,
        count: positions.length,
        totalUsd,
        positions: positions.map((position) => ({
          positionId: position.positionId,
          positionType: position.positionType,
          totalValueUsd: position.totalValueUsd,
          tokens: position.tokens.map((token) => ({
            mint: token.mint,
            symbol: token.symbol,
            amount: token.amount,
            valueUsd: token.valueUsd
          }))
        }))
      },
      null,
      2
    )
  );
}
