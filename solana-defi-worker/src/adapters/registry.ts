import type { DeFiAdapter } from "../types.js";
import { JupiterPerpsAdapter } from "./jupiter-perps.adapter.js";
import { JupiterLendAdapter } from "./jupiter-lend.adapter.js";
import { KaminoLendAdapter } from "./kamino-lend.adapter.js";
import { KaminoLiquidityAdapter } from "./kamino-liquidity.adapter.js";
import { KaminoVaultAdapter } from "./kamino-vault.adapter.js";
import { LoopscaleAdapter } from "./loopscale.adapter.js";
import { LuloAdapter } from "./lulo.adapter.js";
import { MarinadeNativeAdapter } from "./marinade-native.adapter.js";
import { MeteoraDlmmAdapter } from "./meteora-dlmm.adapter.js";
import { MeteoraDynamicAdapter } from "./meteora-dynamic.adapter.js";
import { MarginfiAdapter } from "./marginfi.adapter.js";
import { OrcaWhirlpoolAdapter } from "./orca-whirlpool.adapter.js";
import { RaydiumAmmAdapter } from "./raydium-amm.adapter.js";
import { RaydiumClmmAdapter } from "./raydium-clmm.adapter.js";
import { SaveAdapter } from "./save.adapter.js";

export const adapters: DeFiAdapter[] = [
  new JupiterLendAdapter(),
  new KaminoLendAdapter(),
  new KaminoVaultAdapter(),
  new KaminoLiquidityAdapter(),
  new LoopscaleAdapter(),
  new LuloAdapter(),
  new MarinadeNativeAdapter(),
  new RaydiumClmmAdapter(),
  new RaydiumAmmAdapter(),
  new OrcaWhirlpoolAdapter(),
  new MeteoraDlmmAdapter(),
  new MeteoraDynamicAdapter(),
  new MarginfiAdapter(),
  new SaveAdapter(),
  new JupiterPerpsAdapter()
];
