# Lodestone Agent Playbook

This playbook is the default collaboration pattern for Lodestone when rate limits
or cost matter. It is **harness-agnostic**: it describes roles and judgement, not
specific model IDs or a particular agent runtime. Whatever agent is driving (Claude
Code, Codex, or otherwise) reads the same rules and maps them to whatever models
and subagent mechanism it actually has.

The driving ("main") agent keeps ownership of architecture, final decisions, patch
integration, verification, and safety. Cheaper or lower-effort helpers are used as
narrow assistants for bounded read-only work, obvious patch review, or small
isolated implementation tasks.

## Goals

- Spend strong-model time on the parts that actually need it.
- Avoid weaker helpers making broad edits or losing code structure.
- Keep personal data, credentials, and database state safe.
- Reduce repeated context loading and unnecessary exploration.
- Make helper work easy to validate or discard.

## Roles, Not Models

There is no routing table. The old version prescribed specific model IDs per task
and a fixed "always spawn a subagent for X" matrix — in practice that was ignored,
because no harness auto-routes by it and spawning has real overhead. Instead, think
in **roles**, and let the acting agent decide per task whether delegation is
actually worth it.

| Role | What it does | Can edit? | Model tier |
|---|---|---|---|
| **Trunk owner** (main agent) | Product direction, model/health-signal design, BLE/Polar SDK reasoning, DB migrations, data-safety calls, patch integration, verification | Yes | The strongest model available; do not delegate these decisions |
| **Scout** | Bounded read-only lookup: "where is X handled?", grep/trace, fact extraction | No | Lightest model that can plausibly succeed |
| **Worker** | Small, isolated implementation or tests in *named* files only | Yes, named files only | Mid tier; trunk owner reviews and verifies |
| **Reviewer** | Second-opinion critique of a diff, findings only | No | A *different* model from the author where possible (see Multi-Model Review) |

Two principles instead of a matrix:

1. **Default to handling the trunk in-session.** Only spawn a helper when it's
   genuinely more efficient — bounded, verifiable, and not something the main agent
   would have to re-derive anyway. Reflexive spawning wastes context.
2. **When you do delegate, pick the lightest model that can plausibly succeed, and
   escalate on failure rather than pre-emptively.** If the harness can run a helper
   at a lower model or reasoning tier, do so explicitly — nothing auto-selects this.

## When To Delegate

Delegate only when all of these are true:

- The task is bounded and has a clear output.
- The helper can work without blocking the main agent's immediate next step.
- The helper does not need broad project judgement.
- The result can be checked quickly by compile, tests, grep, or review.

Do not delegate:

- Ambiguous health-model decisions.
- SDK/BLE interpretation where false confidence is dangerous.
- Database deletion, migration, or personal-data handling.
- UI rewrites touching large composables unless the write scope is very explicit.
- Anything where the main agent would need to redo the same exploration anyway.

## Write-Scope Rules

Workers that edit code must be told:

- They are not alone in the codebase.
- They must not revert or overwrite unrelated edits.
- They may edit only the named files/functions.
- They must list changed files in their final answer.
- They must not do broad formatting or cleanup outside their scope.

Prefer one worker per file or one worker per clearly independent slice. Avoid
parallel edits to the same file.

## Multi-Model Review

A second model is most valuable as an **adversarial critic** — a different
perspective catches what a single author misses. It is least valuable for routine
implementation (two ways to write the same function). So reach for other models
mainly for *review and critique*, and treat all of their output as advisory: the
trunk owner still inspects, patches, and verifies.

**Use these freely — no need to ask first.** When a review or scout pass would help,
just run it. These calls are read-only/advisory and pre-authorised; the only hard
limit is the data guard below (never send personal health data, exports, or
credentials to any model). Local models send nothing off the machine at all.

**Pick a reviewer from a different family than whatever is driving.** The value is
the outside perspective, so the most useful reviewer is one that did *not* write the
code. If **Codex** is the trunk agent, a `codex` review is effectively a self-review
and adds little — lean on `gemini` and the local models. If **Claude** (or anything
else) is driving, `codex` and `gemini` are the strong outside opinions. The
`review-guard` config names `codex` as primary, but treat that as a sensible default
for a *non-Codex* host, not a rule — the runner drops the host's own family when it
would just be self-review.

All of the following are already installed on this machine. None is mandatory; pick
what fits the change.

- **`scripts/review-guard review --context "..."`** — the canonical gate. Reads
  `.codex/review-guard.json` and orchestrates the configured reviewers
  (currently: `codex` primary, `gemini` + `local-qwen` fallback). Prefer this over
  invoking reviewers by hand, because it applies project scope/privacy settings.
- **GPT / `codex` CLI** — OpenAI's coding agent. A genuinely different model family
  from the trunk, so it's a strong second opinion. Non-interactive:
  `codex exec "review this diff: ..."`, or `codex exec review` to review the current
  repo state.
- **`gemini` CLI** — Gemini has historically been strong specifically for review.
  Non-interactive and read-only: `gemini --approval-mode plan -p "..."`.
- **Local sidecar (Gemma / Qwen)** — `scripts/ask_local_model.py --prompt "..."`.
  Free, always-available *supplement*, not a gate. The server at
  `http://127.0.0.1:8000/v1` exposes Qwen and Gemma; the sidecar auto-selects unless
  `LOCAL_MODEL_NAME` is set. Best for one-file/one-diff/one-function questions.
- **CodeRabbit (`cr`)** — the heaviest reviewer, but heavily rate-limited/gated.
  Use it as a pre-PR backstop, not in the inner edit loop; don't block on it.

Keep external/local review calls small: one file, one function, or one diff hunk.
Never pass credentials, private exports, pulled phone databases, or personal health
payloads to any model. Treat failures as non-blocking and fall back to normal
inspection.

## Token-Saving Defaults

- Start with `rg`, `rg --files`, targeted reads, and `git diff --stat` instead of
  reading whole files.
- Read only the function or composable being changed plus nearby callers.
- Avoid re-reading large files after a patch unless compile or diff suggests trouble.
- Prefer concise progress updates and concise final summaries.
- Do not paste long command outputs back to the user unless they asked for them.
- Use scripts/checks to answer repeated data questions instead of manual SQLite
  spelunking.
- Keep generated reports in `/tmp` unless they are meant to become durable docs.
- Do not browse the web unless the fact is current, external, or explicitly requested.
- When a plan exists, implement the next small slice rather than re-litigating the
  whole plan.

## Safety Defaults

- Never print or commit credentials, tokens, `.env` files, Garmin sessions, Polar
  cloud data, or local database exports containing personal data.
- Treat `polar-cloud-data/`, Garmin DB/session files, and pulled phone databases as
  sensitive.
- Before destructive changes, inspect `git status --short` and confirm scope.
- Prefer additive scripts/docs over risky rewrites when exploring.
- Run `git diff --check` after manual edits.
- Run the smallest meaningful build/test command before reporting success.

## Standard Main-Agent Workflow

1. Inspect `git status --short` and the relevant plan/diff.
2. Decide which work must stay local and which helper tasks are safe.
3. Delegate only bounded side tasks.
4. Continue non-overlapping local work while helpers run.
5. Review helper output instead of blindly trusting it.
6. Apply the patch.
7. Verify with compile/tests/scripts.
8. Summarise outcome, verification, and remaining risks.

## PR Workflow Default

This project is a two-person workflow: the user and the agent. Do not open PRs as
parking places for unfinished work. When the user asks for a PR, treat that as a
request to publish work that is complete enough to review and merge.

- Open a ready-for-review PR by default.
- Use a draft PR only when the user explicitly asks for a draft/WIP PR, or when the
  agent is deliberately signalling that the work is not complete.
- If the work is not ready, say so directly before opening a PR instead of quietly
  encoding that uncertainty as draft status.
- Prefer getting the feature finished, verified, and ready to land before PR
  creation.

## Helper Task Templates

Reusable prompt templates live in `docs/agent-tasks/`:

- `codebase-scout.md`
- `patch-review.md`
- `small-worker.md`
- `docs-summary.md`

These describe the *role*; substitute whatever model/agent the current harness
provides.
