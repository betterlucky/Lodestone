#!/usr/bin/env python3
"""Small research-only Polar AccessLink v4 probe.

This does not belong to the production Android workflow. It is intended to
compare cloud-exported Polar data with the Loop SDK data already in Lodestone.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

from polar_accesslink_tokens import get_access_token, load_local_env


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
        default=None,
        help="AccessLink bearer token override. By default, .env.polar is loaded and refreshed when needed.",
    )
    parser.add_argument(
        "--env-file",
        default=".env.polar",
        help="Local Polar env file containing client credentials and refresh token.",
    )
    parser.add_argument(
        "--force-refresh",
        action="store_true",
        help="Refresh the access token before fetching.",
    )
    parser.add_argument(
        "--ppi-samples",
        action="store_true",
        help="Fetch /ppi-samples with features=samples one day at a time.",
    )
    return parser.parse_args()


def fetch_json(
    endpoint: str,
    token: str,
    from_date: str,
    to_date: str,
    features: tuple[str, ...] = (),
) -> object:
    params: list[tuple[str, str]] = [("from", from_date), ("to", to_date)]
    params.extend(("features", feature) for feature in features)
    query = urllib.parse.urlencode(params)
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


def date_range(from_date: str, to_date: str) -> list[str]:
    start = dt.date.fromisoformat(from_date)
    end = dt.date.fromisoformat(to_date)
    days = []
    current = start
    while current < end:
        days.append(current.isoformat())
        current += dt.timedelta(days=1)
    return days


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
    args = parse_args()
    load_local_env(Path(args.env_file))
    token = args.token
    if not token:
        try:
            token = get_access_token(args.env_file, force_refresh=args.force_refresh)
        except Exception as error:  # noqa: BLE001 - CLI should provide a clear setup message.
            print(str(error), file=sys.stderr)
            return 2
    if not token:
        print(
            "Missing token. Create .env.polar with polar_accesslink_oauth.py.",
            file=sys.stderr,
        )
        return 2

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    endpoints = tuple(args.endpoint or DEFAULT_ENDPOINTS)
    if args.ppi_samples and "ppi-samples" not in endpoints:
        endpoints = (*endpoints, "ppi-samples")
    manifest = {
        "from": args.from_date,
        "to": args.to_date,
        "endpoints": [],
        "purpose": "research_only_cloud_backfill_probe",
    }

    for endpoint in endpoints:
        if endpoint == "ppi-samples" and args.ppi_samples:
            daily_payloads = []
            failures = []
            for day in date_range(args.from_date, args.to_date):
                next_day = (dt.date.fromisoformat(day) + dt.timedelta(days=1)).isoformat()
                try:
                    daily_payloads.append(
                        fetch_json(endpoint, token, day, next_day, features=("samples",))
                    )
                except urllib.error.HTTPError as error:
                    if error.code == 401 and not args.token:
                        token = get_access_token(args.env_file, force_refresh=True)
                        daily_payloads.append(
                            fetch_json(endpoint, token, day, next_day, features=("samples",))
                        )
                        continue
                    body = error.read().decode("utf-8", errors="replace")
                    failures.append(
                        {
                            "date": day,
                            "status": "http_error",
                            "code": error.code,
                            "body_preview": body[:500],
                        }
                    )
                except Exception as error:  # noqa: BLE001 - CLI probe should report all failures.
                    failures.append({"date": day, "status": "error", "message": str(error)})
            payload = {
                "dailyResponses": daily_payloads,
                "failures": failures,
                "requestMode": "features=samples_one_day_at_a_time",
            }
            output_path = out_dir / f"{endpoint}_samples_{args.from_date}_to_{args.to_date}.json"
            output_path.write_text(json.dumps(payload, indent=2, sort_keys=True))
            summary = summarize_payload(endpoint, payload)
            manifest["endpoints"].append(
                {
                    "endpoint": endpoint,
                    "status": "ok" if not failures else "partial",
                    "summary": summary,
                    "file": str(output_path),
                    "features": ["samples"],
                    "failures": failures,
                }
            )
            print(f"{endpoint} samples: {summary} -> {output_path}")
            continue
        try:
            payload = fetch_json(endpoint, token, args.from_date, args.to_date)
        except urllib.error.HTTPError as error:
            if error.code == 401 and not args.token:
                token = get_access_token(args.env_file, force_refresh=True)
                payload = fetch_json(endpoint, token, args.from_date, args.to_date)
            else:
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
