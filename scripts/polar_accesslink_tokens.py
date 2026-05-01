#!/usr/bin/env python3
"""Token helpers for the research-only Polar AccessLink scripts."""

from __future__ import annotations

import base64
import json
import os
import time
import urllib.parse
import urllib.request
from pathlib import Path


TOKEN_URL = "https://auth.polar.com/oauth/token"
DEFAULT_ENV_FILE = ".env.polar"
REFRESH_SKEW_SECONDS = 10 * 60


def load_env_file(path: Path = Path(DEFAULT_ENV_FILE)) -> dict[str, str]:
    values: dict[str, str] = {}
    if not path.exists():
        return values
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip().strip('"').strip("'")
    return values


def load_local_env(path: Path = Path(DEFAULT_ENV_FILE)) -> None:
    for key, value in load_env_file(path).items():
        os.environ.setdefault(key, value)


def refresh_access_token(
    client_id: str,
    client_secret: str,
    refresh_token: str,
) -> dict[str, object]:
    credentials = base64.b64encode(f"{client_id}:{client_secret}".encode("utf-8")).decode("ascii")
    body = urllib.parse.urlencode(
        {
            "grant_type": "refresh_token",
            "refresh_token": refresh_token,
        }
    ).encode("utf-8")
    request = urllib.request.Request(
        TOKEN_URL,
        data=body,
        method="POST",
        headers={
            "Authorization": f"Basic {credentials}",
            "Content-Type": "application/x-www-form-urlencoded",
            "Accept": "application/json",
        },
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.loads(response.read().decode("utf-8"))


def write_env(
    path: Path,
    values: dict[str, str],
    tokens: dict[str, object],
    issued_at_epoch: int | None = None,
) -> None:
    issued_at = issued_at_epoch or int(time.time())
    expires_in = int(tokens.get("expires_in") or values.get("POLAR_TOKEN_EXPIRES_IN") or 0)
    refresh_token = str(tokens.get("refresh_token") or values.get("POLAR_REFRESH_TOKEN") or "")
    merged = {
        **values,
        "POLAR_ACCESS_TOKEN": str(tokens.get("access_token") or values.get("POLAR_ACCESS_TOKEN") or ""),
        "POLAR_REFRESH_TOKEN": refresh_token,
        "POLAR_TOKEN_SCOPE": str(tokens.get("scope") or values.get("POLAR_TOKEN_SCOPE") or ""),
        "POLAR_TOKEN_EXPIRES_IN": str(expires_in),
        "POLAR_TOKEN_ISSUED_AT": str(issued_at),
        "POLAR_TOKEN_EXPIRES_AT": str(issued_at + expires_in if expires_in else ""),
    }
    ordered_keys = [
        "POLAR_CLIENT_ID",
        "POLAR_CLIENT_SECRET",
        "POLAR_REDIRECT_URI",
        "POLAR_ACCESS_TOKEN",
        "POLAR_REFRESH_TOKEN",
        "POLAR_TOKEN_SCOPE",
        "POLAR_TOKEN_EXPIRES_IN",
        "POLAR_TOKEN_ISSUED_AT",
        "POLAR_TOKEN_EXPIRES_AT",
    ]
    lines = ["# Local Polar AccessLink research credentials. Do not commit."]
    for key in ordered_keys:
        if key in merged:
            lines.append(f"{key}={merged[key]}")
    for key in sorted(set(merged) - set(ordered_keys)):
        lines.append(f"{key}={merged[key]}")
    lines.append("")
    path.write_text("\n".join(lines))
    path.chmod(0o600)


def get_access_token(
    env_file: str | Path = DEFAULT_ENV_FILE,
    force_refresh: bool = False,
) -> str:
    path = Path(env_file)
    values = load_env_file(path)
    for key, value in values.items():
        os.environ.setdefault(key, value)

    access_token = os.environ.get("POLAR_ACCESS_TOKEN") or values.get("POLAR_ACCESS_TOKEN")
    refresh_token = os.environ.get("POLAR_REFRESH_TOKEN") or values.get("POLAR_REFRESH_TOKEN")
    client_id = os.environ.get("POLAR_CLIENT_ID") or values.get("POLAR_CLIENT_ID")
    client_secret = os.environ.get("POLAR_CLIENT_SECRET") or values.get("POLAR_CLIENT_SECRET")
    expires_at_raw = os.environ.get("POLAR_TOKEN_EXPIRES_AT") or values.get("POLAR_TOKEN_EXPIRES_AT")

    expires_at = int(expires_at_raw) if expires_at_raw and expires_at_raw.isdigit() else None
    should_refresh = force_refresh or not access_token
    if expires_at is not None:
        should_refresh = should_refresh or time.time() >= (expires_at - REFRESH_SKEW_SECONDS)

    if should_refresh:
        if not client_id or not client_secret or not refresh_token:
            raise RuntimeError("Missing Polar refresh credentials. Re-run polar_accesslink_oauth.py.")
        tokens = refresh_access_token(client_id, client_secret, refresh_token)
        write_env(path, values, tokens)
        access_token = str(tokens.get("access_token") or "")

    if not access_token:
        raise RuntimeError("Missing POLAR_ACCESS_TOKEN. Re-run polar_accesslink_oauth.py.")
    return access_token
