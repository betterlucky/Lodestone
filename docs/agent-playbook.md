# Lodestone Agent Playbook

This playbook is the default collaboration pattern for Lodestone when rate limits or cost matter.

The main agent should preserve ownership of architecture, final decisions, patch integration, verification, and safety. Cheaper or lower-effort agents should be used as narrow helpers for bounded read-only work, obvious patch review, or small isolated implementation tasks.

## Goals

- Spend strong-model time on the parts that actually need it.
- Avoid weaker agents making broad edits or losing code structure.
- Keep personal data, credentials, and database state safe.
- Reduce repeated context loading and unnecessary exploration.
- Make subagent work easy to validate or discard.

## Default Routing

| Task type | Default route | Effort | Can edit? | Notes |
|---|---|---:|---|---|
| Product direction, model design, BLE/Polar SDK reasoning, DB migrations, data safety | Main agent, `gpt-5.5` | medium/high | Yes | Do not delegate final decisions. |
| Codebase lookup or "where is X handled?" | Local Qwen for one-file/function questions; otherwise explorer subagent, `gpt-5.4-mini` | low/medium | No | Ask for exact files/functions and line refs. |
| Patch review | Local Qwen for small diffs; otherwise explorer or worker subagent, `gpt-5.4-mini` or `gpt-5.3-codex` | medium | No | Findings only, ordered by severity. |
| Small isolated implementation | Worker subagent, `gpt-5.3-codex` | medium | Yes, named files only | Main agent reviews and verifies. |
| Docs, summaries, changelogs, prompt drafting | Local Qwen when available, otherwise `gpt-5.4-mini` | low | No | Advisory only. |
| Tests for already-understood logic | Worker subagent, `gpt-5.3-codex` | medium | Yes, test files only | Main agent runs tests. |
| Refactors across multiple files | Main agent or carefully split workers | medium | Yes, disjoint ownership only | Avoid parallel edits to the same file. |

## When To Delegate

Delegate only when all of these are true:

- The task is bounded and has a clear output.
- The helper can work without blocking the main agent's immediate next step.
- The helper does not need broad project judgement.
- The result can be checked quickly by compile, tests, grep, or review.

Do not delegate:

- Ambiguous health model decisions.
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

Prefer one worker per file or one worker per clearly independent slice.

## Token-Saving Defaults

- Start with `rg`, `rg --files`, targeted `sed`, and `git diff --stat` instead of reading whole files.
- Read only the function or composable being changed plus nearby callers.
- Avoid re-reading large files after a patch unless compile or diff suggests trouble.
- Prefer concise progress updates and concise final summaries.
- Do not paste long command outputs back to the user unless they asked for them.
- Use scripts/checks to answer repeated data questions instead of manual SQLite spelunking.
- Keep generated reports in `/tmp` unless they are meant to become durable docs.
- Do not browse the web unless the fact is current, external, or explicitly requested.
- When a plan exists, implement the next small slice rather than re-litigating the whole plan.

## Safety Defaults

- Never print or commit credentials, tokens, `.env` files, Garmin sessions, Polar cloud data, or local database exports containing personal data.
- Treat `polar-cloud-data/`, Garmin DB/session files, and pulled phone databases as sensitive.
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
6. Patch with `apply_patch`.
7. Verify with compile/tests/scripts.
8. Summarise outcome, verification, and remaining risks.

## Recommended Subagent Templates

Use the templates in `docs/agent-tasks/`:

- `codebase-scout.md`
- `patch-review.md`
- `small-worker.md`
- `docs-summary.md`

## Local Qwen / oMLX Sidecar

Use `scripts/ask_local_model.py` when a local OpenAI-compatible model is running.

Local model tasks should be read-only by default:

- Summarise one doc.
- Explain one function.
- List likely duplicated helpers.
- Draft non-critical docs.
- Generate first-pass test ideas.
- Review a small diff for obvious regressions.

Local model output is advisory. The main agent must still inspect, patch, and verify.
Do not describe a one-off `ask_local_model.py` pass as the standard guard.
For PR-backed work or anything that calls for the standard review guard, run:

```bash
scripts/review-guard review --context "Describe the PR or change under review"
```

The guard wrapper reads `.codex/review-guard.json` and orchestrates the configured
reviewers, which may include local models and Gemini. A sidecar Qwen call is only
one optional input, not a substitute for the configured guard.

Default local endpoint:

```bash
scripts/ask_local_model.py --prompt "Question here"
```

The script delegates to the shared review-guard model sidecar. It defaults to
`http://127.0.0.1:8000/v1`, dynamically selects from available local models
unless `LOCAL_MODEL_NAME` is set, and uses API key `5555`. Override with
`LOCAL_MODEL_BASE_URL`, `LOCAL_MODEL_NAME`, or `LOCAL_MODEL_API_KEY`.

Keep local calls small:

- Prefer one file, one function, or one diff hunk.
- Avoid streaming, huge prompts, concurrent calls, and whole-repo tasks.
- Do not pass credentials, private exports, pulled phone databases, or personal health payloads.
- Treat failures as non-blocking; fall back to normal inspection or Codex subagents.
