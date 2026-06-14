Before substantial work in this repository, read and follow `docs/agent-playbook.md`.
When starting from a fresh Codex project or when old thread context is missing, also read `docs/codex-handover.md`.

Use `scripts/ask_local_model.py` for bounded read-only local sidecar tasks (Gemma/Qwen) when the local OpenAI-compatible server is running. Treat local model output as advisory only. See `docs/agent-playbook.md` § Multi-Model Review for the full set of review/scout routes (Codex, Gemini CLI, local sidecar, CodeRabbit) — all pre-authorised, no need to ask before using them.
