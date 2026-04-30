# Polar Cloud Backfill Probe

This is a research-only route for checking whether Polar Flow / AccessLink can
backfill historical data that the Android BLE SDK no longer returns from the
Loop.

It is not currently a viable production app dependency because it relies on the
Polar cloud and therefore on Flow-side synchronisation. It is useful for
analysis because it may tell us whether `PPI_247` was always available, or
whether offline recording changed the Loop state and caused the normal feed to
remain active.

## Question

Can Polar cloud data provide historical `ppi-samples` for dates before the
offline-recording experiment, especially `2026-04-20` to `2026-04-24`?

If yes, compare those samples against the local Lodestone database:

- Flow/cloud has PPI before `2026-04-25`, but Lodestone BLE does not:
  likely local/device retention or SDK access limitation.
- Neither Flow/cloud nor Lodestone has PPI before `2026-04-25`:
  likely the feed was not active/available then.
- Flow/cloud has sparse PPI before offline recording but dense PPI during the
  offline period:
  supports the hypothesis that offline recording changed device state.

## Local Setup

Create a private `.env.polar` file in the repository root:

```bash
POLAR_ACCESS_TOKEN=...
```

Do not commit this file. It is ignored by git.

Then run:

```bash
python3 scripts/polar_accesslink_probe.py \
  --from-date 2026-04-20 \
  --to-date 2026-04-30
```

Outputs are written to `polar-cloud-data/`, also ignored by git.

## Endpoints Probed

The probe currently fetches:

- `ppi-samples`
- `nightly-recharge-results`
- `sleeps`
- `sleep-wake-vectors`

These are for comparison only. The Android app should continue to use direct
Loop sync for the live prototype workflow unless we deliberately decide
otherwise later.
