# Garmin Sidecar Plan

Garmin is currently a fallback / comparison data source rather than the primary
Lodestone data path. If Polar Loop direct sync remains reliable enough, we may
drop Garmin from the active workflow.

Keep this note as the deferred plan if Garmin becomes important again.

## Current Status

- `garmin-givemydata` can populate the useful Garmin sidecar tables:
  `hrv`, `hrv_timeline`, `sleep`, `stress`, `body_battery`, `heart_rate`,
  `respiration`, `spo2`, `daily_summary`, and related context.
- On `2026-05-01`, the Garmin DB was refreshed successfully through
  `2026-05-01` after filesystem access was restored and SeleniumBase's
  `uc_driver` was updated to match the installed Chrome patch version.
- The failure mode before that was misleading: Chrome/UC appeared broken, but
  Codex had lost write access to `~/garmin-givemydata`, so it could not update
  driver files, logs, browser profile state, or the DB reliably.

## If We Keep Garmin

Move the Garmin runner/data directory under a Codex-writable managed path, for
example:

```text
/Users/daveharris/dev/HealthMonitor/local/garmin-givemydata/
```

Keep all personal Garmin data ignored by git:

```text
local/
*.db
*.db-shm
*.db-wal
garmin_session.json
browser_profile/
debug.log
```

Then update the Garmin wrapper scripts to point at that managed location rather
than `~/garmin-givemydata`.

## Required Preflight

Before launching Chrome/Selenium, the sync wrapper should fail fast unless it
can write to:

- `debug.log`
- `garmin.db`, `garmin.db-shm`, and `garmin.db-wal`
- `browser_profile/`
- SeleniumBase driver directory inside the Garmin virtualenv

It should also check:

- installed Chrome version
- `uc_driver --version`
- whether the major/patch versions are close enough, or refresh `uc_driver`
  before starting the sync

## Preferred Sync Command

Use the broad profile while Garmin remains useful:

```bash
./venv/bin/python garmin_givemydata.py --profile all --no-files
```

Only use narrower profiles for debugging. The analysis layer expects broad
coverage where available, especially HRV timeline, stress, body battery, HR,
respiration, SpO2, and sleep.

## Do Not Do

- Do not overwrite the real Garmin DB from a temp run unless the temp DB has
  clearly advanced beyond the previous maximum date.
- Do not commit Garmin DBs, sessions, browser profiles, exports, or logs.
- Do not rely on Chrome/Selenium errors as evidence that Garmin has no new data;
  confirm by checking table max dates.
