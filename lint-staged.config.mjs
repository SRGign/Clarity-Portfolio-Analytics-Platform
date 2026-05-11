export default {
  "frontend/**/*.{ts,tsx}": () => "npm run build:frontend",
  "solana-defi-worker/**/*.ts": () => "npm run build:worker",
  "*.{json,md}": "prettier --check --ignore-unknown",
};
