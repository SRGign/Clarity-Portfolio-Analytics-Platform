import "dotenv/config";
import express from "express";
import { fetchPositions, listProtocols } from "./service.js";
import { logger } from "./logger.js";
import { rpcEndpointHost } from "./rpc.js";

const app = express();
const startedAt = Date.now();

app.use(express.json({ limit: "1mb" }));

app.get("/health", (_request, response) => {
  response.status(200).json({
    status: "ok",
    uptime: Math.floor((Date.now() - startedAt) / 1000),
    rpcEndpointHost
  });
});

app.get("/defi/protocols", (_request, response) => {
  response.status(200).json({ protocols: listProtocols() });
});

app.post("/defi/positions", async (request, response) => {
  try {
    const body = request.body as { walletAddress?: string; protocols?: string[] };
    if (!body.walletAddress) {
      response.status(200).json({
        walletAddress: "",
        fetchedAt: new Date().toISOString(),
        positions: [],
        errors: [{ protocolId: "request", message: "walletAddress is required" }]
      });
      return;
    }

    const result = await fetchPositions({
      walletAddress: body.walletAddress,
      protocols: body.protocols
    });

    response.status(200).json(result);
  } catch (error) {
    logger.warn({ error }, "request failed");
    response.status(200).json({
      walletAddress: request.body?.walletAddress ?? "",
      fetchedAt: new Date().toISOString(),
      positions: [],
      errors: [{ protocolId: "request", message: error instanceof Error ? error.message : String(error) }]
    });
  }
});

const port = Number(process.env.PORT ?? 8787);
app.listen(port, () => {
  logger.info({ port }, "solana-defi-worker listening");
});

export { app };
