import { WalletRecord } from "@/types/portfolio";

const DB_NAME = "pnl-tracker";
const DB_VERSION = 2;
const WALLET_STORE = "wallets";
const META_STORE = "meta";
const PORTFOLIO_SNAPSHOT_PREFIX = "portfolio-snapshot-v1:";

type MetaRecord = {
  key: string;
  value: string;
};

export type PortfolioSnapshot = {
  date: string;
  totalUsd: number;
  solanaSpotUsd?: number;
  unreportedDefiUsd?: number;
};

export type PortfolioSnapshotPair = {
  today: PortfolioSnapshot | null;
  prev: PortfolioSnapshot | null;
};

export type PortfolioSnapshotInput = {
  totalUsd: number;
  solanaSpotUsd?: number;
  unreportedDefiUsd?: number;
};

function openDatabase(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION);
    request.onerror = () => reject(request.error);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains(WALLET_STORE)) {
        db.createObjectStore(WALLET_STORE, { keyPath: "normalizedAddress" });
      }
      if (db.objectStoreNames.contains("snapshots")) {
        db.deleteObjectStore("snapshots");
      }
      if (!db.objectStoreNames.contains(META_STORE)) {
        db.createObjectStore(META_STORE, { keyPath: "key" });
      }
    };
    request.onsuccess = () => resolve(request.result);
  });
}

async function withStore<T>(
  storeName: string,
  mode: IDBTransactionMode,
  action: (store: IDBObjectStore) => IDBRequest<T>,
): Promise<T> {
  const db = await openDatabase();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(storeName, mode);
    const store = tx.objectStore(storeName);
    const request = action(store);
    request.onerror = () => reject(request.error);
    request.onsuccess = () => resolve(request.result);
    tx.oncomplete = () => db.close();
    tx.onerror = () => reject(tx.error);
  });
}

export async function listWallets(): Promise<WalletRecord[]> {
  const result = await withStore(WALLET_STORE, "readonly", (store) => store.getAll());
  return (result as WalletRecord[]).sort((left, right) =>
    left.createdAt.localeCompare(right.createdAt),
  );
}

export async function saveWallet(wallet: WalletRecord): Promise<void> {
  await withStore(WALLET_STORE, "readwrite", (store) => store.put(wallet));
}

export async function removeWallet(normalizedAddress: string): Promise<void> {
  await withStore(WALLET_STORE, "readwrite", (store) => store.delete(normalizedAddress));
}

export async function getMeta(key: string): Promise<string | null> {
  const result = await withStore(META_STORE, "readonly", (store) => store.get(key));
  return (result as MetaRecord | undefined)?.value ?? null;
}

export async function setMeta(key: string, value: string): Promise<void> {
  await withStore(META_STORE, "readwrite", (store) => store.put({ key, value }));
}

function portfolioSnapshotKey(walletHash: string): string {
  return `${PORTFOLIO_SNAPSHOT_PREFIX}${walletHash}`;
}

export async function readPortfolioSnapshotPair(walletHash: string): Promise<PortfolioSnapshotPair> {
  if (!walletHash) {
    return { today: null, prev: null };
  }
  const raw = await getMeta(portfolioSnapshotKey(walletHash));
  if (!raw) {
    return { today: null, prev: null };
  }
  try {
    const parsed = JSON.parse(raw) as Partial<PortfolioSnapshotPair>;
    return {
      today: normalizeSnapshot(parsed.today),
      prev: normalizeSnapshot(parsed.prev),
    };
  } catch {
    return { today: null, prev: null };
  }
}

export async function recordPortfolioSnapshot(
  walletHash: string,
  date: string,
  input: number | PortfolioSnapshotInput,
): Promise<PortfolioSnapshotPair> {
  const snapshot = normalizeSnapshotInput(date, input);
  if (!walletHash || !snapshot) {
    return readPortfolioSnapshotPair(walletHash);
  }
  const existing = await readPortfolioSnapshotPair(walletHash);
  let next: PortfolioSnapshotPair;
  if (!existing.today) {
    next = { today: snapshot, prev: existing.prev };
  } else if (existing.today.date === date) {
    next = { today: snapshot, prev: existing.prev };
  } else {
    next = { today: snapshot, prev: existing.today };
  }
  await setMeta(portfolioSnapshotKey(walletHash), JSON.stringify(next));
  return next;
}

function normalizeSnapshotInput(date: string, input: number | PortfolioSnapshotInput): PortfolioSnapshot | null {
  const totalUsd = typeof input === "number" ? input : input.totalUsd;
  if (!Number.isFinite(totalUsd) || totalUsd <= 0) {
    return null;
  }
  const snapshot: PortfolioSnapshot = { date, totalUsd };
  if (typeof input !== "number") {
    if (isFiniteSnapshotValue(input.solanaSpotUsd)) {
      snapshot.solanaSpotUsd = input.solanaSpotUsd;
    }
    if (isFiniteSnapshotValue(input.unreportedDefiUsd)) {
      snapshot.unreportedDefiUsd = input.unreportedDefiUsd;
    }
  }
  return snapshot;
}

function normalizeSnapshot(snapshot: unknown): PortfolioSnapshot | null {
  if (!snapshot || typeof snapshot !== "object") {
    return null;
  }
  const candidate = snapshot as Partial<PortfolioSnapshot>;
  if (typeof candidate.date !== "string" || typeof candidate.totalUsd !== "number") {
    return null;
  }
  const normalized: PortfolioSnapshot = { date: candidate.date, totalUsd: candidate.totalUsd };
  if (isFiniteSnapshotValue(candidate.solanaSpotUsd)) {
    normalized.solanaSpotUsd = candidate.solanaSpotUsd;
  }
  if (isFiniteSnapshotValue(candidate.unreportedDefiUsd)) {
    normalized.unreportedDefiUsd = candidate.unreportedDefiUsd;
  }
  return normalized;
}

function isFiniteSnapshotValue(value: unknown): value is number {
  return typeof value === "number" && Number.isFinite(value) && value >= 0;
}
