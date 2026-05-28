#!/usr/bin/env python3
"""Small, bounded helper for OpenAI-compatible local models.

This is intentionally not an agent harness. It sends one prompt to one local
model and prints the assistant text, keeping context and output limits explicit
so fragile local servers are less likely to fall over.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path


DEFAULT_BASE_URL = "http://127.0.0.1:8000/v1"
DEFAULT_MODEL = "Qwen3-8B-MLX-4bit"
DEFAULT_TIMEOUT_SECONDS = 60
REVIEW_GUARD_HINT_PATTERN = re.compile(r"\b(review|pr|pull request|diff|guard)\b", re.IGNORECASE)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Ask a bounded question of a local OpenAI-compatible model.")
    parser.add_argument("--base-url", default=os.environ.get("LOCAL_MODEL_BASE_URL", DEFAULT_BASE_URL))
    parser.add_argument("--api-key", default=os.environ.get("LOCAL_MODEL_API_KEY", "5555"))
    parser.add_argument("--model", default=os.environ.get("LOCAL_MODEL_NAME", DEFAULT_MODEL))
    parser.add_argument("--system", default="You are a concise read-only coding assistant. Do not claim to edit files.")
    parser.add_argument("--prompt", help="Prompt text. If omitted, stdin is used.")
    parser.add_argument("--file", action="append", default=[], help="Append a file's contents to the prompt. Repeatable.")
    parser.add_argument("--max-file-chars", type=int, default=30_000)
    parser.add_argument("--max-tokens", type=int, default=800)
    parser.add_argument("--temperature", type=float, default=0.1)
    parser.add_argument("--timeout", type=int, default=DEFAULT_TIMEOUT_SECONDS)
    parser.add_argument("--json", action="store_true", help="Print the full JSON response instead of assistant text.")
    return parser.parse_args()


def read_prompt(args: argparse.Namespace) -> str:
    parts: list[str] = []
    if args.prompt:
        parts.append(args.prompt)
    elif not sys.stdin.isatty():
        parts.append(sys.stdin.read())

    for file_name in args.file:
        path = Path(file_name).expanduser()
        text = path.read_text(encoding="utf-8", errors="replace")
        if len(text) > args.max_file_chars:
            text = text[: args.max_file_chars] + "\n\n[truncated by ask_local_model.py]\n"
        parts.append(f"\n\n--- FILE: {path} ---\n{text}")

    prompt = "\n".join(part for part in parts if part.strip())
    if not prompt.strip():
        raise SystemExit("No prompt supplied. Use --prompt, --file, or stdin.")
    return prompt


def maybe_warn_about_review_guard(prompt: str) -> None:
    if not REVIEW_GUARD_HINT_PATTERN.search(prompt):
        return
    print(
        "Note: ask_local_model.py is a single-model advisory sidecar. "
        "For the standard multi-review guard, run `scripts/review-guard review`.",
        file=sys.stderr,
    )


def post_chat_completion(args: argparse.Namespace, prompt: str) -> dict:
    url = args.base_url.rstrip("/") + "/chat/completions"
    payload = {
        "model": args.model,
        "messages": [
            {"role": "system", "content": args.system},
            {"role": "user", "content": prompt},
        ],
        "temperature": args.temperature,
        "max_tokens": args.max_tokens,
        "stream": False,
    }
    request = urllib.request.Request(
        url,
        data=json.dumps(payload).encode("utf-8"),
        headers={
            "Authorization": f"Bearer {args.api_key}",
            "Content-Type": "application/json",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=args.timeout) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        body = error.read().decode("utf-8", errors="replace")
        raise SystemExit(f"Local model HTTP {error.code}: {body}") from error
    except urllib.error.URLError as error:
        raise SystemExit(f"Could not reach local model at {url}: {error}") from error


def main() -> None:
    args = parse_args()
    prompt = read_prompt(args)
    maybe_warn_about_review_guard(prompt)
    response = post_chat_completion(args, prompt)
    if args.json:
        print(json.dumps(response, indent=2))
        return
    try:
        print(response["choices"][0]["message"]["content"])
    except (KeyError, IndexError, TypeError) as error:
        raise SystemExit(f"Unexpected local model response: {json.dumps(response)[:2000]}") from error


if __name__ == "__main__":
    main()
