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

## Follow-Up Work

Create hardening tasks for:

- data-quality provenance for cloud/API backfilled lanes
- an incident/audit report around Flow-use windows
- Settings copy and controls that discourage casual Flow use
- firmware-source reporting that distinguishes runtime firmware, saved firmware,
  Flow-visible firmware, and public support-page firmware where available
