#!/usr/bin/env bash
set -euo pipefail

if [ -n "${MODEL_SIDECAR:-}" ]; then
  exec "$MODEL_SIDECAR" "$@"
elif command -v ask_model >/dev/null 2>&1; then
  exec ask_model "$@"
elif [ -f "$HOME/.codex/skills/review-guard/scripts/model_sidecar.py" ]; then
  exec python3 "$HOME/.codex/skills/review-guard/scripts/model_sidecar.py" "$@"
elif [ -f "$HOME/.claude/skills/review-guard/scripts/model_sidecar.py" ]; then
  exec python3 "$HOME/.claude/skills/review-guard/scripts/model_sidecar.py" "$@"
else
  echo "model sidecar not found. Install the Kanban review-guard skill or put ask_model on PATH." >&2
  exit 1
fi
