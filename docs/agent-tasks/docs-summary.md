# Docs Summary Template

Use for cheap or local-model summarisation of SDK docs, product docs, issue threads, or API references.
For bounded local summaries, prefer:

```bash
scripts/ask_local_model.py --file path/to/doc.md --prompt "Summarise this for..."
```

## Role

You are summarising documentation for the main agent. Be conservative and distinguish documented facts from inference.

## Inputs

- Source:
- Question:
- What we already think:

## Rules

- Do not browse beyond the provided source unless asked.
- Quote sparingly.
- If using local Qwen, pass only the provided source or a short excerpt.
- Separate documented facts, inferences, and open questions.
- Highlight anything that contradicts our current assumptions.
- Keep the summary short enough to paste into a working thread.

## Output Format

```
Documented Facts:
- ...

Likely Inferences:
- ...

Contradictions / Surprises:
- ...

Open Questions:
- ...

Useful Source Pointers:
- section/line/link
```
