"use client";

import { FormEvent, useMemo, useState } from "react";
import type { ReactNode } from "react";

import { requestAiPortfolioChat, requestAiPortfolioSummary, walletSetHash } from "@/lib/portfolio";
import type {
  AiAdvisorAction,
  AiAdvisorError,
  AiAdvisorOpportunity,
  AiAdvisorRisk,
  AiAdvisorSummary,
  AiChatMessage,
  WalletRecord,
} from "@/types/portfolio";

type Props = {
  wallets: WalletRecord[];
  chains: string[];
};

type AdvisorState = "idle" | "loading" | "summary" | "error" | "disabled";

export function AiAdvisorBlock({ wallets, chains }: Props) {
  const [state, setState] = useState<AdvisorState>("idle");
  const [summary, setSummary] = useState<AiAdvisorSummary | null>(null);
  const [messages, setMessages] = useState<AiChatMessage[]>([]);
  const [input, setInput] = useState("");
  const [error, setError] = useState<AiAdvisorError | null>(null);
  const [chatLoading, setChatLoading] = useState(false);
  const walletHash = useMemo(() => walletSetHash(wallets), [wallets]);

  async function analyze() {
    if (wallets.length === 0 || chains.length === 0) {
      return;
    }

    setState("loading");
    setError(null);
    setSummary(null);
    setMessages([]);

    try {
      const response = await requestAiPortfolioSummary(wallets, chains);
      if ("error" in response) {
        setError(response);
        setState(response.error === "AI_DISABLED" ? "disabled" : "error");
        return;
      }
      setSummary(response);
      setState("summary");
    } catch {
      setError({ error: "AI_ERROR", message: "AI request failed" });
      setState("error");
    }
  }

  async function sendMessage(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const text = input.trim();
    if (!text || chatLoading || state !== "summary") {
      return;
    }

    const nextMessages: AiChatMessage[] = [...messages, { role: "user", text }];
    setMessages(nextMessages);
    setInput("");
    setChatLoading(true);

    try {
      const response = await requestAiPortfolioChat(wallets, chains, nextMessages);
      if ("error" in response) {
        setError(response);
        return;
      }
      setMessages([...nextMessages, { role: "model", text: response.reply }]);
    } catch {
      setError({ error: "AI_ERROR", message: "AI chat request failed" });
    } finally {
      setChatLoading(false);
    }
  }

  if (wallets.length === 0) {
    return null;
  }

  return (
    <section className="s-panel s-ai-panel" data-wallet-hash={walletHash}>
      <div className="s-panel-hd s-panel-hd-violet">
        <span>AI PORTFOLIO ADVISOR</span>
        <span className="s-badge">{state.toUpperCase()}</span>
      </div>

      {state === "idle" ? (
        <div className="s-ai-idle">
          <div>
            <strong>Run a portfolio risk readout</strong>
            <p>Concentration, liquidity, yield drag, and action priorities from the current wallet scope.</p>
          </div>
          <button className="s-ai-action" type="button" onClick={() => void analyze()}>
            ANALYZE_PORTFOLIO
          </button>
        </div>
      ) : null}

      {state === "loading" ? (
        <div className="s-ai-loading" role="status" aria-live="polite">
          <span className="s-ai-spinner" />
          <span className="mono">AI ANALYZING...</span>
        </div>
      ) : null}

      {state === "disabled" ? (
        <div className="s-ai-state">
          <strong>AI_ADVISOR_DISABLED</strong>
          <p>Configure GEMINI_API_KEY to enable portfolio analysis.</p>
        </div>
      ) : null}

      {state === "error" ? (
        <div className="s-ai-state is-error">
          <strong>{error?.error === "AI_PARSE_ERROR" ? "AI_RESPONSE_ERROR" : "AI_ERROR"}</strong>
          <p>{error?.message ?? "Retry the analysis request."}</p>
          <button className="s-ai-action" type="button" onClick={() => void analyze()}>
            RETRY
          </button>
        </div>
      ) : null}

      {state === "summary" && summary ? (
        <div className="s-ai-summary-shell">
          <header className="s-ai-score-row">
            <div className="s-ai-score" style={{ borderColor: healthColor(summary.healthScore) }}>
              <span>HEALTH_SCORE</span>
              <strong style={{ color: healthColor(summary.healthScore) }}>{summary.healthScore}</strong>
              <em>{summary.healthLabel}</em>
            </div>
            <p className="s-ai-oneliner">"{summary.oneLiner}"</p>
          </header>

          <div className="s-ai-columns">
            <AdvisorColumn title="RISKS">
              {summary.risks.slice(0, 3).map((risk) => (
                <RiskItem key={`${risk.severity}-${risk.title}`} risk={risk} />
              ))}
            </AdvisorColumn>
            <AdvisorColumn title="OPPORTUNITIES">
              {summary.opportunities.slice(0, 3).map((opportunity) => (
                <OpportunityItem key={`${opportunity.effort}-${opportunity.title}`} opportunity={opportunity} />
              ))}
            </AdvisorColumn>
            <AdvisorColumn title="ACTIONS">
              {summary.actions.slice(0, 4).map((action) => (
                <ActionItem key={`${action.priority}-${action.action}`} action={action} />
              ))}
            </AdvisorColumn>
          </div>

          <div className="s-ai-chat">
            <div className="s-ai-chat-log">
              {messages.length === 0 ? (
                <p className="s-ai-chat-empty">Ask about risk, sizing, liquidity, or DeFi exposure.</p>
              ) : (
                messages.map((message, index) => (
                  <div key={`${message.role}-${index}`} className={`s-ai-message is-${message.role}`}>
                    <span>{message.role === "user" ? "USER" : "AI"}</span>
                    <p>{message.text}</p>
                  </div>
                ))
              )}
              {chatLoading ? <div className="s-ai-typing mono">AI_TYPING...</div> : null}
            </div>
            <form className="s-ai-chat-form" onSubmit={sendMessage}>
              <input
                value={input}
                onChange={(event) => setInput(event.target.value)}
                placeholder="Ask about your portfolio..."
                disabled={chatLoading}
              />
              <button type="submit" disabled={chatLoading || input.trim().length === 0}>
                SEND -&gt;
              </button>
            </form>
          </div>
        </div>
      ) : null}
    </section>
  );
}

function AdvisorColumn({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="s-ai-column">
      <h3>{title}</h3>
      <div className="s-ai-column-list">{children}</div>
    </section>
  );
}

function RiskItem({ risk }: { risk: AiAdvisorRisk }) {
  return (
    <article className="s-ai-item">
      <span className={`s-ai-chip is-${risk.severity.toLowerCase()}`}>{risk.severity}</span>
      <strong>{risk.title}</strong>
      <p>{risk.detail}</p>
    </article>
  );
}

function OpportunityItem({ opportunity }: { opportunity: AiAdvisorOpportunity }) {
  return (
    <article className="s-ai-item">
      <span className="s-ai-chip is-opportunity">{opportunity.effort}</span>
      <strong>{opportunity.title}</strong>
      <p>{opportunity.detail}</p>
    </article>
  );
}

function ActionItem({ action }: { action: AiAdvisorAction }) {
  return (
    <article className="s-ai-item">
      <span className={`s-ai-chip is-${action.priority.toLowerCase()}`}>{action.priority}</span>
      <strong>{action.action}</strong>
      <p>{action.rationale}</p>
    </article>
  );
}

function healthColor(score: number): string {
  if (score >= 80) return "#00C97B";
  if (score >= 60) return "#7EC97B";
  if (score >= 40) return "#FFB800";
  if (score >= 20) return "#FF8C00";
  return "#FF4455";
}
