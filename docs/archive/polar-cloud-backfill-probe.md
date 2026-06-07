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
POLAR_CLIENT_ID=...
POLAR_CLIENT_SECRET=...
POLAR_REDIRECT_URI=http://localhost:8765/callback
POLAR_ACCESS_TOKEN=...
POLAR_REFRESH_TOKEN=...
```

Do not commit this file. It is ignored by git.

Use the OAuth helper for the first login:

```bash
python3 scripts/polar_accesslink_oauth.py
```

The probe script refreshes the access token automatically when it is missing,
near expiry, or when Polar returns `401`. A forced refresh can be tested with:

```bash
python3 scripts/polar_accesslink_probe.py \
  --from-date 2026-04-30 \
  --to-date 2026-05-01 \
  --endpoint sleeps \
  --force-refresh
```

Then run:

```bash
python3 scripts/polar_accesslink_probe.py \
  --from-date 2026-04-20 \
  --to-date 2026-04-30 \
  --ppi-samples
```

Polar's `ppi-samples` endpoint returns only date/modified metadata unless
`features=samples` is requested. That feature can only be requested one day at a
time, so the probe splits PPI sample fetches into daily calls when
`--ppi-samples` is present.

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
