# Materione Code-Mode Benchmark Runbook (`#385` & `#349`)

This harness runs the 29 corpus tasks (`T01`–`T29`), 5 follow-ups (`F1`–`F5`), and 12 vague requests (`V01`–`V12`) against:
- Real TypeScript SDK type-checking (`webui/src/lib/codemode/typecheck`)
- Real QuickJS-WASM sandbox (`webui/src/lib/codemode/sandbox`)
- Real JVM `authoring.transaction` dry-run & apply oracle (`:plugin:benchmarkOracle` / `CodemodeBenchmarkOracleMain`) or fast in-memory oracle (`--oracle memory`)

Zero benchmark bytes enter the production SPA bundle (`webui/benchmark/` is outside `webui/src/`).

---

## 1. `$0.00` Local Smoke Test (Offline Mock Provider)

Runs the full pipeline (checker + QuickJS sandbox + real JVM oracle) with scripted mock responses for `$0.00`:

```powershell
npm --prefix webui run benchmark -- --mock --oracle jvm --tasks T04,T08,T27,F3
```

Quick in-memory full-corpus check:

```powershell
npm --prefix webui run benchmark -- --mock --oracle memory --suite all
```

---

## 2. Paid `#385` & `#349` Runs (Owner Only — Never Run by Agents)

Per `CLAUDE.md` Cloudflare cost guardrails, only the owner runs live paid evaluations against Cloudflare AI Gateway.

### Required Environment Variables

```powershell
$env:CF_ACCOUNT_ID = "<cloudflare-account-id>"
$env:CF_AIG_GATEWAY_ID = "<gateway-id>"
$env:CF_AIG_TOKEN = "<cf-aig-token>"
```

### Budgeted Commands

1. **Cheap vs Mid-Tier vs Escalation Routing (`#385`)**:
```powershell
npm --prefix webui run benchmark -- --oracle jvm --models claude-haiku-5-5 --strategy always-cheap --max-dollars 1.00 --out-dir webui/build/bench-cheap
npm --prefix webui run benchmark -- --oracle jvm --models claude-sonnet-4-5 --strategy always-mid --max-dollars 5.00 --out-dir webui/build/bench-mid
npm --prefix webui run benchmark -- --oracle jvm --models claude-haiku-5-5 --mid-model claude-sonnet-4-5 --strategy escalate --max-dollars 3.00 --out-dir webui/build/bench-escalate
```

2. **Context Size Ablation (`index` vs `full-dts`, `#385`)**:
```powershell
npm --prefix webui run benchmark -- --oracle jvm --models claude-haiku-5-5 --context full-dts --max-dollars 2.00 --out-dir webui/build/bench-full-dts
```

3. **Code Mode vs Tool-per-Operation Baseline (`#349`)**:
```powershell
npm --prefix webui run benchmark -- --oracle jvm --models claude-haiku-5-5 --mode tool-baseline --max-dollars 2.00 --out-dir webui/build/bench-baseline
```

Use `--model-override id='{"inputPicosPerToken":...}'` if dashboard pricing differs from preset values. Frontier models (`opus`, `o1-pro`, `o3-pro`, `gpt-4.5`, `gpt-5-pro`, or `>$5/1M` input / `>$25/1M` output) are rejected before any request is sent.
