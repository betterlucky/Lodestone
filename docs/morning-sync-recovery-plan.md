# Morning Sync Recovery Plan

This note captures the current recovery direction for morning sync failures,
especially long `PPI_247` pulls that may fail when the phone moves out of
reliable Bluetooth range.

## Current Observation

On 2026-05-26, the morning core sync fetched sleep and Nightly Recharge quickly,
then `PPI_247` timed out after the app had been backgrounded/frozen. A scheduled
PPI retry later succeeded and advanced the morning retry state to final sleep
report checks. No data loss was observed.

## Pause / Resume

The Polar SDK call used by Lodestone returns `PPI_247` as a completed list. It
does not expose progress, a cursor, or a batch callback that Lodestone can pause
and resume safely.

For now, treat retry as the safe resumable unit:

- Cancel or time out an unhealthy pull.
- Re-run the same date range when the connection is solid again.
- Let `ppi247_day_raw` persistence deduplicate by date, sample start, sample
  count, and trigger type.
- Rebuild derived epochs after new raw batches are stored.

This is less elegant than true pause/resume, but it avoids pretending the SDK
offers a checkpoint it does not currently expose.

## Recovery Direction

The next implementation should prefer connection-aware retry over longer waits:

- Check the Loop connection before starting the expensive PPI pull.
- During the morning sync UI, tell the user to keep the phone near the Loop.
- If the SDK reports disconnection during a long pull, fail fast into the
  existing retry chain rather than waiting for the full timeout.
- Use a short, bounded active reconnect loop during the morning window.
- Keep the existing scheduled retry path as the fallback.

Avoid duplicate sync work by preserving the single sync mutex and by keeping
PPI persistence idempotent.

## Current Implementation

`PPI_247` sync now runs under a connection monitor. If the runtime state stops
matching the active connected Loop while the PPI pull is in flight, Lodestone
records the domain as failed and lets the existing morning retry chain attempt
the same date range again.

The Today screen also shows a short `Stay near Loop` nudge while the morning
sync is running.
