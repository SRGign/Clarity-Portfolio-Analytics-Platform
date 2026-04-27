import { getCachedPrice, setCachedPrice } from "./cache.js";
import { logger } from "./logger.js";

type DefiLlamaResponse = {
  coins?: Record<string, { price?: number }>;
};

type JupiterResponse = {
  data?: Record<string, { price?: number }>;
};

export async function getPrice(mint: string): Promise<number> {
  const cached = getCachedPrice(mint);
  if (cached !== undefined) {
    return cached;
  }

  try {
    const prices = await getPrices([mint]);
    return prices.get(mint) ?? 0;
  } catch (error) {
    logger.warn({ mint, error }, "price lookup failed");
    setCachedPrice(mint, 0);
    return 0;
  }
}

export async function getPrices(mints: string[]): Promise<Map<string, number>> {
  const uniqueMints = [...new Set(mints.filter(Boolean))];
  const result = new Map<string, number>();
  const missing: string[] = [];

  for (const mint of uniqueMints) {
    const cached = getCachedPrice(mint);
    if (cached !== undefined) {
      result.set(mint, cached);
    } else {
      missing.push(mint);
    }
  }

  if (missing.length === 0) {
    return result;
  }

  const llamaPrices = await fetchDefiLlamaPrices(missing);
  for (const [mint, price] of llamaPrices) {
    result.set(mint, price);
    setCachedPrice(mint, price);
  }

  const stillMissing = missing.filter((mint) => !result.has(mint));
  if (stillMissing.length > 0) {
    const jupiterPrices = await fetchJupiterPrices(stillMissing);
    for (const [mint, price] of jupiterPrices) {
      result.set(mint, price);
      setCachedPrice(mint, price);
    }
  }

  for (const mint of missing) {
    if (!result.has(mint)) {
      logger.warn({ mint }, "price unavailable, using 0");
      result.set(mint, 0);
      setCachedPrice(mint, 0);
    }
  }

  return result;
}

async function fetchDefiLlamaPrices(mints: string[]): Promise<Map<string, number>> {
  const ids = mints.map((mint) => `solana:${mint}`).join(",");
  const response = await fetch(`https://coins.llama.fi/prices/current/${ids}`);
  if (!response.ok) {
    throw new Error(`DefiLlama pricing failed: ${response.status}`);
  }

  const body = (await response.json()) as DefiLlamaResponse;
  const prices = new Map<string, number>();

  for (const mint of mints) {
    const price = body.coins?.[`solana:${mint}`]?.price;
    if (typeof price === "number" && Number.isFinite(price)) {
      prices.set(mint, price);
    }
  }

  return prices;
}

async function fetchJupiterPrices(mints: string[]): Promise<Map<string, number>> {
  const ids = mints.join(",");
  const response = await fetch(`https://price.jup.ag/v6/price?ids=${encodeURIComponent(ids)}`);
  if (!response.ok) {
    throw new Error(`Jupiter pricing failed: ${response.status}`);
  }

  const body = (await response.json()) as JupiterResponse;
  const prices = new Map<string, number>();

  for (const mint of mints) {
    const price = body.data?.[mint]?.price;
    if (typeof price === "number" && Number.isFinite(price)) {
      prices.set(mint, price);
    }
  }

  return prices;
}
