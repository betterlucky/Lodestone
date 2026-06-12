Before substantial work in this repository, read and follow `docs/agent-playbook.md`.
When starting from a fresh Codex project or when old thread context is missing, also read `docs/codex-handover.md`.

**Kanban:** This project is `HealthMonitor` (see `.codex/kanban.json`). For board tasks, follow the shared skill at `~/.claude/skills/kanban` (`SKILL.md` + `shared.md`) — do not duplicate or fork it. Read the card and its parent before work; parent closeout owns spawning implementation child cards unless the user says otherwise.

Use `scripts/ask_local_model.py` for bounded read-only local Qwen sidecar tasks when the local OpenAI-compatible server is running. Treat local model output as advisory only.
