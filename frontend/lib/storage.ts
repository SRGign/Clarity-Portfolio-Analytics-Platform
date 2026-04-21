import { WalletRecord } from "@/types/portfolio";

const DB_NAME = "pnl-tracker";
const DB_VERSION = 2;
const WALLET_STORE = "wallets";
const META_STORE = "meta";

type MetaRecord = {
  key: string;
  value: string;
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
