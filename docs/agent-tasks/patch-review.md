# Patch Review Template

Use for cheap review of a current diff.
For small diffs, local Qwen can be used as a first-pass reviewer:

```bash
git diff -- path/to/file | scripts/ask_local_model.py --prompt "Review this diff for correctness issues. Findings only."
```

## Role

You are reviewing a patch for bugs, regressions, broken assumptions, missing tests, and unsafe behaviour. Findings first. Do not edit files.

## Inputs

- Patch or files to review:
- Intended behaviour:
- Known risk areas:

## Rules

- Do not rewrite the patch.
- Do not comment on style unless it hides a bug.
- If using local Qwen, keep the diff small and verify any finding manually.
- Prioritise correctness, data safety, lifecycle/race issues, and user-visible regressions.
- Include file and line references.
- If no findings, say so and list residual risks.

## Output Format

```
Findings:
- [P1/P2/P3] path:line — issue and impact

Questions:
- ...

Residual Risks:
- ...
```
