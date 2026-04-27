import { mkdir, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { adapters } from "../src/adapters/registry.js";
import type { DeFiAdapter, Position } from "../src/types.js";

const TEST_WALLET = process.env.TEST_WALLET?.trim() || process.env.MAINNET_SMOKE_WALLET?.trim() || "";
const ADAPTER_TIMEOUT_MS = 30_000;
const SNAPSHOT_PATH = resolve(process.cwd(), "tests", "readiness-snapshot.md");
const shouldRun = process.env.RUN_READINESS_REPORT === "true";
const maybeDescribe = shouldRun ? describe : describe.skip;
const DEFERRED_ADAPTERS = new Map<string, string>([
  ["lulo", "Lulo account balance/earnings API requires developer-dashboard access"]
]);

maybeDescribe("adapter readiness report", () => {
  it(
    "prints and persists readiness for every registered adapter",
    async () => {
      if (!TEST_WALLET) {
        throw new Error("TEST_WALLET or MAINNET_SMOKE_WALLET must be set to run readiness report");
      }

      const rows: ReadinessRow[] = [];

      for (const adapter of adapters) {
        const deferredReason = DEFERRED_ADAPTERS.get(adapter.protocolId);
        if (deferredReason) {
          rows.push({
            protocol: adapter.protocolId,
            status: "DEFERRED",
            positions: null,
            totalUsd: null,
            error: deferredReason
          });
          continue;
        }

        if (adapter.protocolId === "jupiter-perps") {
          rows.push({
            protocol: adapter.protocolId,
            status: "STUB",
            positions: null,
            totalUsd: null,
            error: "not implemented"
          });
          continue;
        }

        rows.push(await runAdapter(adapter, TEST_WALLET));
      }

      const report = renderReport(rows, TEST_WALLET, new Date());
      console.log(report);
      await mkdir(dirname(SNAPSHOT_PATH), { recursive: true });
      await writeFile(SNAPSHOT_PATH, `${report}\n`, "utf8");

      expect(rows).toHaveLength(adapters.length);
    },
    adapters.length * (ADAPTER_TIMEOUT_MS + 5_000)
  );
});

async function runAdapter(adapter: DeFiAdapter, walletAddress: string): Promise<ReadinessRow> {
  try {
    const positions = await withTimeout(adapter.fetchPositions(walletAddress), ADAPTER_TIMEOUT_MS);
    return {
      protocol: adapter.protocolId,
      status: "OK",
      positions: positions.length,
      totalUsd: positions.reduce((sum, position) => sum + position.totalValueUsd, 0),
      error: ""
    };
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    return {
      protocol: adapter.protocolId,
      status: message === "timeout" ? "TIMEOUT" : "ERROR",
      positions: null,
      totalUsd: null,
      error: message
    };
  }
}

async function withTimeout<T>(promise: Promise<T>, timeoutMs: number): Promise<T> {
  let timeout: NodeJS.Timeout | undefined;
  try {
    return await Promise.race([
      promise,
      new Promise<T>((_resolve, reject) => {
        timeout = setTimeout(() => reject(new Error("timeout")), timeoutMs);
      })
    ]);
  } finally {
    if (timeout) {
      clearTimeout(timeout);
    }
  }
}

function renderReport(rows: ReadinessRow[], walletAddress: string, timestamp: Date): string {
  const lines = [
    "# Solana DeFi Worker Readiness Snapshot",
    "",
    `Timestamp: ${timestamp.toISOString()}`,
    `Wallet: ${walletAddress}`,
    "",
    "| Protocol | Status | Positions | Total USD | Errors |",
    "|---|---:|---:|---:|---|",
    ...rows.map((row) =>
      [
        row.protocol,
        row.status,
        row.positions === null ? "-" : String(row.positions),
        row.totalUsd === null ? "-" : formatUsd(row.totalUsd),
        escapeMarkdown(row.error)
      ].join(" | ")
    ).map((line) => `| ${line} |`)
  ];

  return lines.join("\n");
}

function formatUsd(value: number): string {
  return `$${Math.round(value * 100) / 100}`;
}

function escapeMarkdown(value: string): string {
  return value.replace(/\|/g, "\\|").replace(/\r?\n/g, " ");
}

type ReadinessStatus = "OK" | "ERROR" | "TIMEOUT" | "STUB" | "DEFERRED";

type ReadinessRow = {
  protocol: string;
  status: ReadinessStatus;
  positions: number | null;
  totalUsd: number | null;
  error: string;
};
