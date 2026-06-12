# Polar Flow Maintenance And Data Integrity

This note records the current operating policy after the June 2026 Flow/Loop
data incident. It should be treated as active project guidance until we
deliberately test and replace it.

## Current Rule

Lodestone is the normal Loop reader. Polar Flow is a controlled maintenance and
recovery tool, not part of the daily data-collection flow.

Do not open Flow to casually check firmware, sleep finalisation, or device state
while Lodestone is collecting data. If Flow must be used, treat it as a
maintenance event and audit affected dates before trusting modelling outputs.

## June 2026 Incident

During a trial check-in while Flow was active, Flow appeared to remove or hide a
large amount of locally available Loop data, causing Lodestone sync failures.
Most missing data appears to have been backfilled from Polar cloud / Flow-synced
data using the local backfill route, but affected dates should be regarded as
recovered-with-provenance rather than ordinary Loop-local captures.

Operational implications:

- Freeze firmware unless there is a deliberate reason to update.
- Avoid Flow during normal collection.
- If Flow is used, record the date/time and run a data completeness audit.
- Mark cloud/API backfill as provenance, not as indistinguishable local capture.
- Keep `cloud_backfill:` raw rows as durable provenance when maintenance
  rebuilds raw lanes into derived tables; normal raw-retention pruning should
  not delete them.
- Treat any readiness/model output created during the disruption window as
  suspect until regenerated from the repaired dataset.

## Firmware Discrepancy

As of 2026-06-12, the public Polar 360 firmware support page lists **6.0.57** as
released on 2026-05-13:

<https://support.polar.com/en/polar-360-firmware-updates>

Flow, however, only surfaced **5.0.55** for the Loop during the incident, and a
separate Codex thread also treated 5.0.55 as the latest version. Do not collapse
those into a single fact. Record firmware claims with source and timestamp:

- `Polar support page`, checked 2026-06-12: latest listed Polar 360 firmware is
  6.0.57.
- `Polar Flow app`, observed 2026-06-12: only 5.0.55 surfaced for this device.
- `Agent/thread memory`, prior to this note: 5.0.55 was incorrectly or
  incompletely treated as latest.

Possible explanations include staged rollout, device/product-family difference,
regional/app gating, stale app/device metadata, or the Loop not being treated
identically to generic Polar 360 firmware despite apparent hardware kinship.
Until verified, avoid relying on Flow's update surface as the sole source of
firmware truth.

## App Copy

User-facing copy should frame Flow actions as maintenance only. Avoid wording
that implies Flow is a routine daily partner for sleep finalisation or firmware
checking. The app may offer a controlled release/disconnect action, but it
should warn that Flow can affect what data remains available locally and that
an audit may be needed afterward.

Firmware copy should name the source of the value. In-app firmware values are
local observations, such as runtime BLE metadata or a saved selected-device
setting. Flow-visible firmware and public support-page firmware are separate
manual/source-tagged observations.

## Incident And Provenance Reports

Use the daily completeness report when checking a date or range:

```bash
python3 scripts/daily_data_completeness.py \
  --health-db /path/to/health-monitor-probe.db \
  --start-date 2026-06-01 \
  --end-date 2026-06-12
```

The report labels raw lane provenance from `requestedRange`. A value beginning
`cloud_backfill:` is reported as cloud/API backfill; ordinary sync ranges are
reported as local Loop capture.

Treat `cloud_backfill:` as a small cross-tool contract. If the backfill writer
changes that prefix, update the Kotlin raw-retention protection and the Python
report classifier together.

Implementation note: the app's rebuild/prune maintenance preserves
`cloud_backfill:` rows for raw PPI, HR, skin-temperature, and activity lanes.
If a later successful local Loop sync replaces a date's raw lane before rebuild,
that can supersede the cloud copy; use the incident report to distinguish what
remains in the database.

For Flow-specific reconstruction, use:

```bash
python3 scripts/flow_incident_provenance_report.py \
  --health-db /path/to/health-monitor-probe.db \
  --start-date 2026-06-01 \
  --end-date 2026-06-12 \
  --flow-visible-firmware 5.0.55 \
  --flow-observed-date 2026-06-12 \
  --public-firmware-version 6.0.57 \
  --public-firmware-release-date 2026-05-13 \
  --public-checked-date 2026-06-12
```

This script is read-only and does not print raw payloads. It summarises each
date/lane as local Loop, cloud/API backfill, mixed, or unknown, then lists
firmware observations with source labels.

## Follow-Up Work

Create hardening tasks for:

- surface provenance in more user-facing History/Signals views if this proves
  necessary during testing
- decide whether Flow maintenance events should become first-class manual
  markers rather than doc/report context
