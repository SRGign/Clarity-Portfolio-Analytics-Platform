# Clarity

Crypto portfolio tracker for EVM wallets with a terminal-style dashboard.

## What it does

- Track one or more EVM wallets
- Aggregate balances across supported networks
- Show total portfolio value, allocation, and history
- Surface lending and DeFi positions in the same view

## Stack

- `frontend`: Next.js 15, React 19, Tailwind CSS 4
- `backend`: Spring Boot 3, Java 17, SQLite

## Run locally

Backend:

```powershell
cd backend
$env:ALCHEMY_API_KEY="your-key"
.\gradlew.bat bootRun
```

Frontend:

```powershell
cd frontend
npm.cmd install
npm.cmd run dev
```

App URLs:

- Frontend: `http://localhost:3000`
- Backend API: `http://localhost:8080`

## Optional environment variables

Backend:

- `ALCHEMY_API_KEY`
- `PORTFOLIO_GOLDRUSH_API_KEY`

Frontend:

- `NEXT_PUBLIC_API_BASE_URL`

## Notes

- Secrets should stay in local environment variables and never be committed.
- Build output, logs, databases, screenshots, and workspace metadata are excluded from git.
