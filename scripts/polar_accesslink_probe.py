#!/usr/bin/env python3
"""Small research-only Polar AccessLink v4 probe.

This does not belong to the production Android workflow. It is intended to
compare cloud-exported Polar data with the Loop SDK data already in Lodestone.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


BASE_URL = "https://www.polaraccesslink.com/v4/data"
DEFAULT_ENDPOINTS = (
    "ppi-samples",
    "nightly-recharge-results",
    "sleeps",
    "sleep-wake-vectors",
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Fetch selected Polar AccessLink v4 data endpoints for a date range."
    )
    parser.add_argument("--from-date", required=True, help="Start date, YYYY-MM-DD.")
    parser.add_argument("--to-date", required=True, help="End date, YYYY-MM-DD.")
    parser.add_argument(
        "--endpoint",
        action="append",
        choices=DEFAULT_ENDPOINTS,
        help="Endpoint to fetch. Repeat to fetch several. Defaults to the research set.",
    )
    parser.add_argument(
        "--out-dir",
        default="polar-cloud-data",
        help="Output directory. This folder is git-ignored by default.",
    )
    parser.add_argument(
        "--token",
        default=os.environ.get("POLAR_ACCESS_TOKEN"),
        help="AccessLink bearer token. Prefer POLAR_ACCESS_TOKEN or .env.polar locally.",
    )
    return parser.parse_args()


def load_local_env() -> None:
    env_path = Path(".env.polar")
    if not env_path.exists():
        return
    for line in env_path.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))


def fetch_json(endpoint: str, token: str, from_date: str, to_date: str) -> object:
    query = urllib.parse.urlencode({"from": from_date, "to": to_date})
    request = urllib.request.Request(
        f"{BASE_URL}/{endpoint}?{query}",
        headers={
            "Accept": "application/json",
            "Authorization": f"Bearer {token}",
        },
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        raw = response.read().decode("utf-8")
    return json.loads(raw) if raw else None


def summarize_payload(endpoint: str, payload: object) -> str:
    if payload is None:
        return "empty"
    if isinstance(payload, list):
        return f"list(size={len(payload)})"
    if isinstance(payload, dict):
        for key in ("data", "items", "result"):
            value = payload.get(key)
            if isinstance(value, list):
                return f"object(keys={len(payload)}, {key}.size={len(value)})"
        if endpoint == "ppi-samples":
            dates = payload.get("date") or payload.get("dates")
            return f"object(keys={len(payload)}, date={dates})"
        return f"object(keys={len(payload)})"
    return type(payload).__name__


def main() -> int:
    load_local_env()
    args = parse_args()
    if not args.token:
        print(
            "Missing token. Set POLAR_ACCESS_TOKEN or create .env.polar with "
            "POLAR_ACCESS_TOKEN=...",
            file=sys.stderr,
        )
        return 2

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    endpoints = tuple(args.endpoint or DEFAULT_ENDPOINTS)
    manifest = {
        "from": args.from_date,
        "to": args.to_date,
        "endpoints": [],
        "purpose": "research_only_cloud_backfill_probe",
    }

    for endpoint in endpoints:
        try:
            payload = fetch_json(endpoint, args.token, args.from_date, args.to_date)
        except urllib.error.HTTPError as error:
            body = error.read().decode("utf-8", errors="replace")
            manifest["endpoints"].append(
                {
                    "endpoint": endpoint,
                    "status": "http_error",
                    "code": error.code,
                    "body_preview": body[:500],
                }
            )
            print(f"{endpoint}: HTTP {error.code}", file=sys.stderr)
            continue
        except Exception as error:  # noqa: BLE001 - CLI probe should report all failures.
            manifest["endpoints"].append(
                {
                    "endpoint": endpoint,
                    "status": "error",
                    "message": str(error),
                }
            )
            print(f"{endpoint}: {error}", file=sys.stderr)
            continue

        output_path = out_dir / f"{endpoint}_{args.from_date}_to_{args.to_date}.json"
        output_path.write_text(json.dumps(payload, indent=2, sort_keys=True))
        summary = summarize_payload(endpoint, payload)
        manifest["endpoints"].append(
            {
                "endpoint": endpoint,
                "status": "ok",
                "summary": summary,
                "file": str(output_path),
            }
        )
        print(f"{endpoint}: {summary} -> {output_path}")

    manifest_path = out_dir / f"manifest_{args.from_date}_to_{args.to_date}.json"
    manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True))
    print(f"manifest: {manifest_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
