#!/usr/bin/env python3
"""One-shot OAuth helper for the research-only Polar AccessLink probe."""

from __future__ import annotations

import argparse
import base64
import http.server
import json
import os
import socketserver
import sys
import threading
import urllib.error
import urllib.parse
import urllib.request
import webbrowser
from pathlib import Path

from polar_accesslink_tokens import write_env


AUTH_URL = "https://auth.polar.com/oauth/authorize"
TOKEN_URL = "https://auth.polar.com/oauth/token"
DEFAULT_REDIRECT_URI = "http://localhost:8765/callback"
DEFAULT_SCOPES = (
    "ppi_data:read",
    "sleep:read",
    "nightly_recharge:read",
    "devices:read",
    "continuous_samples:read",
    "activity:read",
    "temperature_measurement:read",
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Authorize Polar AccessLink and write tokens to ignored .env.polar."
    )
    parser.add_argument("--client-id", default=os.environ.get("POLAR_CLIENT_ID"))
    parser.add_argument("--client-secret", default=os.environ.get("POLAR_CLIENT_SECRET"))
    parser.add_argument("--redirect-uri", default=os.environ.get("POLAR_REDIRECT_URI", DEFAULT_REDIRECT_URI))
    parser.add_argument("--scope", action="append", help="OAuth scope. Repeat to override defaults.")
    parser.add_argument("--env-file", default=".env.polar")
    parser.add_argument("--no-browser", action="store_true", help="Print URL but do not open browser.")
    return parser.parse_args()


def load_local_env(path: Path) -> None:
    if not path.exists():
        return
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))


class CallbackHandler(http.server.BaseHTTPRequestHandler):
    server: "CallbackServer"

    def do_GET(self) -> None:  # noqa: N802 - stdlib callback name
        parsed = urllib.parse.urlparse(self.path)
        params = urllib.parse.parse_qs(parsed.query)
        self.server.authorization_code = params.get("code", [None])[0]
        self.server.authorization_error = params.get("error", [None])[0]
        body = (
            "Polar authorization captured. You can close this tab."
            if self.server.authorization_code
            else f"Polar authorization failed: {self.server.authorization_error}"
        )
        self.send_response(200 if self.server.authorization_code else 400)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.end_headers()
        self.wfile.write(body.encode("utf-8"))
        threading.Thread(target=self.server.shutdown, daemon=True).start()

    def log_message(self, format: str, *args: object) -> None:
        return


class CallbackServer(socketserver.TCPServer):
    allow_reuse_address = True

    def __init__(self, server_address: tuple[str, int]) -> None:
        super().__init__(server_address, CallbackHandler)
        self.authorization_code: str | None = None
        self.authorization_error: str | None = None


def exchange_code(client_id: str, client_secret: str, code: str, redirect_uri: str) -> dict[str, object]:
    credentials = base64.b64encode(f"{client_id}:{client_secret}".encode("utf-8")).decode("ascii")
    body = urllib.parse.urlencode(
        {
            "grant_type": "authorization_code",
            "code": code,
            "redirect_uri": redirect_uri,
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


def main() -> int:
    env_path = Path(".env.polar")
    load_local_env(env_path)
    args = parse_args()
    client_id = args.client_id or os.environ.get("POLAR_CLIENT_ID")
    client_secret = args.client_secret or os.environ.get("POLAR_CLIENT_SECRET")
    if not client_id or not client_secret:
        print("Set POLAR_CLIENT_ID and POLAR_CLIENT_SECRET in .env.polar or the environment.", file=sys.stderr)
        return 2

    redirect = urllib.parse.urlparse(args.redirect_uri)
    if redirect.hostname not in {"localhost", "127.0.0.1"}:
        print("This helper only supports localhost redirect URIs.", file=sys.stderr)
        return 2
    port = redirect.port or 80
    path = redirect.path or "/"
    scopes = tuple(args.scope or DEFAULT_SCOPES)
    state = base64.urlsafe_b64encode(os.urandom(18)).decode("ascii").rstrip("=")
    query = urllib.parse.urlencode(
        {
            "client_id": client_id,
            "response_type": "code",
            "scope": " ".join(scopes),
            "redirect_uri": args.redirect_uri,
            "state": state,
        }
    )
    authorization_url = f"{AUTH_URL}?{query}"

    print("Starting local callback server.")
    print(f"Redirect URI must be registered in Polar admin: {args.redirect_uri}")
    print("Opening authorization URL...")
    print(authorization_url)
    with CallbackServer((redirect.hostname or "localhost", port)) as server:
        if not args.no_browser:
            webbrowser.open(authorization_url)
        else:
            print("Open the URL above in your browser.")
        server.handle_request()
        if server.authorization_error:
            print(f"Authorization failed: {server.authorization_error}", file=sys.stderr)
            return 1
        if not server.authorization_code:
            print("No authorization code captured.", file=sys.stderr)
            return 1
        tokens = exchange_code(client_id, client_secret, server.authorization_code, args.redirect_uri)

    output_path = Path(args.env_file)
    write_env(
        output_path,
        {
            "POLAR_CLIENT_ID": client_id,
            "POLAR_CLIENT_SECRET": client_secret,
            "POLAR_REDIRECT_URI": args.redirect_uri,
        },
        tokens,
    )
    print(f"Wrote tokens to {output_path} with mode 600.")
    print("Access token expires in", tokens.get("expires_in"), "seconds.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
