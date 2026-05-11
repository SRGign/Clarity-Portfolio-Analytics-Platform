type SessionCacheEnvelope<T> = {
  cachedAt: number;
  data: T;
};

export function scopedSessionCacheKey(prefix: string, parts: string[]): string {
  return `${prefix}:${parts.map((part) => encodeURIComponent(part)).join(":")}`;
}

export function readSessionCache<T>(key: string, ttlMs: number): T | null {
  if (typeof window === "undefined") {
    return null;
  }

  try {
    const raw = window.sessionStorage.getItem(key);
    if (!raw) {
      return null;
    }

    const envelope = JSON.parse(raw) as Partial<SessionCacheEnvelope<T>>;
    if (typeof envelope.cachedAt !== "number" || Date.now() - envelope.cachedAt > ttlMs) {
      window.sessionStorage.removeItem(key);
      return null;
    }

    return envelope.data ?? null;
  } catch {
    return null;
  }
}

export function writeSessionCache<T>(key: string, data: T): void {
  if (typeof window === "undefined") {
    return;
  }

  try {
    const envelope: SessionCacheEnvelope<T> = {
      cachedAt: Date.now(),
      data,
    };
    window.sessionStorage.setItem(key, JSON.stringify(envelope));
  } catch {
    // Cache writes are opportunistic; UI state still owns the live result.
  }
}

export function clearSessionCache(key: string): void {
  if (typeof window === "undefined") {
    return;
  }

  try {
    window.sessionStorage.removeItem(key);
  } catch {
    return;
  }
}
