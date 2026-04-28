#!/usr/bin/env bash
set -euo pipefail

GARMIN_DIR="${GARMIN_DATA_DIR:-$HOME/garmin-givemydata}"
PYTHON_BIN="$GARMIN_DIR/venv/bin/python"
APP_BIN="$GARMIN_DIR/garmin_givemydata.py"

if [[ ! -x "$PYTHON_BIN" ]]; then
  echo "Garmin virtualenv not found at $PYTHON_BIN" >&2
  exit 1
fi

if [[ ! -f "$APP_BIN" ]]; then
  echo "garmin_givemydata.py not found at $APP_BIN" >&2
  exit 1
fi

cd "$GARMIN_DIR"
exec "$PYTHON_BIN" "$APP_BIN" --profile sleep --days 3 --no-files "$@"
