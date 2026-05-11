# Clarity

Clarity is a crypto portfolio intelligence dashboard for tracking wallet balances, portfolio value, P&L, asset allocation, DeFi exposure, benchmark performance, and risk metrics across EVM and Solana wallets.

The product is built for a dense portfolio workflow: add wallets, refresh balances, inspect positions, compare allocation, review portfolio history, analyze risk, and use an AI portfolio advisor when the AI API key is configured.

Clarity is read-only. It does not sign transactions, execute trades, move funds, or manage private keys.

## What The App Does

- Tracks multiple EVM and Solana wallets in one dashboard.
- Aggregates spot balances across supported networks.
- Groups allocation by token, chain, and wallet.
- Shows total net worth, gross assets, liabilities, net exposure, tracked assets, and hidden assets.
- Displays 24h, 7d, and 30d portfolio history.
- Compares portfolio performance against BTC and SOL benchmarks.
- Detects lending and DeFi positions where integrations are available.
- Normalizes Solana protocol positions through a dedicated worker service.
- Calculates risk metrics such as concentration, stablecoin allocation, DeFi exposure, volatility, Sharpe, Sortino, max drawdown, beta, and stress scenarios.
- Provides an AI portfolio summary and chat experience when `GEMINI_API_KEY` is configured.
- Stores local portfolio history snapshots in SQLite.

## Architecture

```text
Browser
  -> frontend/ Next.js app
  -> backend/ Spring Boot API
  -> solana-defi-worker/ Node service for Solana protocol adapters
```

The frontend only calls the backend. The backend owns portfolio aggregation, analytics, history, AI context construction, local persistence, and API responses. The Solana worker is separate because Solana protocol SDKs are Node-oriented and are better isolated from the Java backend.

High-level data flow:

```text
frontend
  -> backend
    -> portfolio data integrations
    -> market and benchmark integrations
    -> SQLite history store
    -> AI model API
    -> solana-defi-worker
      -> Solana RPC endpoint
      -> Solana protocol SDKs
```

## Repository Layout

```text
.
|-- backend/
|   |-- src/main/java/com/pnltracker/
|   |-- src/main/resources/application.yml
|   |-- src/main/resources/schema.sql
|   |-- build.gradle
|   `-- gradlew.bat
|-- frontend/
|   |-- app/
|   |-- components/
|   |-- lib/
|   |-- types/
|   `-- package.json
|-- solana-defi-worker/
|   |-- src/
|   |-- tests/
|   |-- package.json
|   `-- tsconfig.json
|-- .env.example
|-- package.json
|-- lint-staged.config.mjs
`-- README.md
```

## Stack

- Frontend: Next.js 15, React 19, TypeScript, Tailwind CSS 4
- Backend: Java 17, Spring Boot 3.3, JDBC, SQLite, Gradle
- Solana worker: Node.js 20+, Express, TypeScript, Vitest
- Tooling: npm, Husky, lint-staged, Prettier

## Requirements

- Java 17
- Node.js 20+
- npm
- An EVM portfolio API key for live EVM balances
- A Solana RPC endpoint for Solana protocol position coverage
- A Gemini API key for the AI portfolio advisor

The examples below use PowerShell and `npm.cmd`. On Windows this avoids execution policy issues with `npm.ps1`.

## Environment Setup

Local secrets should stay in `.env` files. They are ignored by git.

From the repository root:

```powershell
Copy-Item .env.example .env
Copy-Item backend\.env.example backend\.env
Copy-Item frontend\.env.local.example frontend\.env.local
Copy-Item solana-defi-worker\.env.example solana-defi-worker\.env
```

Edit the copied files before starting the services.

Root `.env` is loaded by the backend through `../.env`:

```properties
SOLANA_DEFI_WORKER_URL=http://localhost:8787
SOLANA_DEFI_WORKER_TIMEOUT_SECONDS=90
GEMINI_API_KEY=key_here
```

Backend `backend/.env`:

```properties
ALCHEMY_API_KEY=your-evm-portfolio-api-key
GEMINI_API_KEY=key_here
```

Frontend `frontend/.env.local`:

```properties
NEXT_PUBLIC_API_BASE_URL=http://localhost:8080/api
```

Solana worker `solana-defi-worker/.env`:

```properties
SOLANA_RPC_URL=https://your-solana-rpc-endpoint.example.com
RPC_FAST_URL=
SOLANA_RPC_RPS=50
PORT=8787
LOG_LEVEL=info
MAINNET_SMOKE_WALLET=
TEST_WALLET=
JUPITER_API_KEY=
JUPITER_PERPS_PROGRAM_ID=
METEORA_DLMM_POOLS=
SAVE_POOL_ADDRESSES=
SAVE_SCAN_ALL_POOLS=false
```

Additional optional integration keys are documented in the example env files and `backend/src/main/resources/application.yml`. Set them only when the corresponding feature is being used.

## Install Dependencies

From the repository root:

```powershell
npm.cmd install
npm.cmd --prefix frontend install
npm.cmd --prefix solana-defi-worker install
```

The backend uses the Gradle wrapper. Java dependencies are resolved when the backend is started, built, or tested.

## Run Locally

Use three terminals.

Terminal 1 - start the Solana worker:

```powershell
cd solana-defi-worker
npm.cmd run dev
```

Health check:

```powershell
Invoke-RestMethod http://localhost:8787/health
```

Terminal 2 - start the backend:

```powershell
cd backend
.\gradlew.bat bootRun
```

Health check through the chain catalog endpoint:

```powershell
Invoke-RestMethod http://localhost:8080/api/chains
```

Terminal 3 - start the frontend:

```powershell
cd frontend
npm.cmd run dev
```

Open the app:

```text
http://localhost:3000
```

Local service URLs:

- Frontend: `http://localhost:3000`
- Backend API: `http://localhost:8080/api`
- Solana worker: `http://localhost:8787`

For EVM-only dashboard work, the backend and frontend are enough. Start the Solana worker when testing Solana protocol positions.

## Supported Networks

The backend chain catalog includes:

- Ethereum
- Polygon
- Arbitrum
- Optimism
- Base
- zkSync Era
- Linea
- Scroll
- Mantle
- Zora
- Blast
- Arbitrum Nova
- Polygon zkEVM
- ZetaChain
- Berachain
- Avalanche
- BNB Chain
- Celo
- Sei
- ApeChain
- Abstract
- Moonbeam
- Solana

## Backend API

Portfolio endpoints:

```text
GET  /api/chains
POST /api/portfolio/overview
POST /api/portfolio/summary
POST /api/portfolio/assets
POST /api/portfolio/positions
POST /api/portfolio/history
```

Analytics endpoints:

```text
POST /api/v1/portfolio/metrics
GET  /api/v1/portfolio/benchmarks?start=<unix_timestamp>
```

AI endpoints:

```text
POST /api/v1/ai/portfolio-summary
POST /api/v1/ai/portfolio-chat
```

Wallet DeFi endpoints:

```text
GET /api/v1/wallets/{address}/defi-positions
GET /api/v1/wallets/{address}/solana/defi-positions
GET /api/v1/wallets/{address}/solana/tokenized-defi-positions
```

## Solana Worker API

The Solana worker is a stateless Express service used by the backend.

```text
GET  /health
GET  /defi/protocols
POST /defi/positions
```

Registered worker adapters:

- Jupiter Lend
- Jupiter Perps
- Kamino Lend
- Kamino Vault
- Kamino Liquidity
- Loopscale
- Lulo
- Marinade Native
- Raydium CLMM
- Raydium AMM
- Orca Whirlpool
- Meteora DLMM
- Meteora Dynamic AMM
- marginfi
- Save Protocol

The worker requires a Solana RPC endpoint. Request throughput is controlled with:

```properties
SOLANA_RPC_RPS=50
```

If the endpoint is rate limited, lower `SOLANA_RPC_RPS` or use an endpoint with higher capacity.

## Frontend Behavior

The dashboard includes:

- wallet entry and wallet list management
- network selection
- manual refresh flow
- total net worth summary
- portfolio history chart
- benchmark overlays
- allocation by token, chain, and wallet
- asset inventory
- lending and DeFi position sections
- portfolio risk metrics
- risk engine view
- AI advisor panel

The frontend stores lightweight UI state and cached responses in browser storage/session storage. Backend history snapshots are stored in SQLite.

## Local Database

The backend default database URL is:

```properties
PORTFOLIO_DB_URL=jdbc:sqlite:./pnl-tracker.db
```

When the backend is run from `backend/`, the local database file is created here:

```text
backend/pnl-tracker.db
```

SQLite files are ignored by git.

## Build

Frontend and Solana worker production build:

```powershell
npm.cmd run build
```

The root build runs:

```powershell
npm.cmd --prefix frontend run build
npm.cmd --prefix solana-defi-worker run build
```

Backend build:

```powershell
cd backend
.\gradlew.bat build
```

## Test

Backend tests from the repository root:

```powershell
.\backend\gradlew.bat -p .\backend test --rerun-tasks
```

Solana worker tests:

```powershell
npm.cmd --prefix solana-defi-worker run test
```

Worker mainnet smoke tests require explicit env:

```powershell
$env:RUN_MAINNET_SMOKE="true"
$env:MAINNET_SMOKE_WALLET="your-wallet"
npm.cmd --prefix solana-defi-worker run test:smoke
```

Worker readiness report:

```powershell
$env:RUN_READINESS_REPORT="true"
$env:TEST_WALLET="your-wallet"
npm.cmd --prefix solana-defi-worker exec -- vitest run tests/readiness-report.test.ts
```

Recommended check before opening a PR:

```powershell
npm.cmd run build
.\backend\gradlew.bat -p .\backend test --rerun-tasks
npm.cmd --prefix solana-defi-worker run test
```

## Pre-Commit Hook

The Husky pre-commit hook runs:

```powershell
npm.cmd run build
npx lint-staged
```

It also scans staged diffs for likely secrets. Backend tests are not part of the hook, so run them manually when backend code changes.

## Troubleshooting

PowerShell blocks `npm.ps1`:

- Use `npm.cmd` instead of `npm`.

Frontend cannot reach the backend:

- Confirm the backend is running on `http://localhost:8080`.
- Confirm `frontend/.env.local` has `NEXT_PUBLIC_API_BASE_URL=http://localhost:8080/api`.
- Restart the frontend after changing `.env.local`.

Solana worker fails on startup:

- Confirm `solana-defi-worker/.env` contains `SOLANA_RPC_URL` or another accepted RPC endpoint variable from the example file.
- Confirm `PORT` is not already in use.
- Restart the worker after changing `.env`.

Solana worker receives rate limit errors:

- Lower `SOLANA_RPC_RPS`.
- Use an RPC endpoint with enough capacity for protocol account discovery.

Backend creates no local history yet:

- Refresh the portfolio after wallets and chains are selected.
- Confirm `backend/pnl-tracker.db` can be created in the backend working directory.
- Check that the requested chains are supported by `/api/chains`.

AI advisor requests fail:

- Confirm `GEMINI_API_KEY` is set in root `.env` or `backend/.env`.
- Restart the backend after changing the key.
