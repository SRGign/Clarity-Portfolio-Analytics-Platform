export type DeFiCategory = "lending" | "clmm" | "amm" | "staking" | "perps" | "yield";

export type PositionType =
  | "deposit"
  | "borrow"
  | "lp"
  | "staked"
  | "reward"
  | "perp_long"
  | "perp_short";

export interface PositionToken {
  mint: string;
  symbol: string;
  decimals: number;
  amount: number;
  priceUsd: number;
  valueUsd: number;
}

export interface Position {
  protocolId: string;
  protocolName: string;
  category: DeFiCategory | string;
  positionType: PositionType;
  positionId: string;
  tokens: PositionToken[];
  totalValueUsd: number;
  pendingRewards?: PositionToken[];
  metadata?: Record<string, unknown>;
}

export interface DeFiAdapter {
  protocolId: string;
  protocolName: string;
  category: DeFiCategory;
  fetchPositions(walletAddress: string): Promise<Position[]>;
}

export interface PositionsRequest {
  walletAddress: string;
  protocols?: string[];
}

export interface ProtocolError {
  protocolId: string;
  message: string;
}

export interface PositionsResponse {
  walletAddress: string;
  fetchedAt: string;
  positions: Position[];
  errors: ProtocolError[];
}

export interface ProtocolDescriptor {
  protocolId: string;
  protocolName: string;
  category: DeFiCategory;
}
