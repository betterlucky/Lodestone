# Polling And Monitoring Decisions

Snapshot of current Lodestone polling and monitoring decisions. If this conflicts
with newer implementation notes, treat it as guidance rather than a source of
truth.

## Morning Recovery

- Final morning status can use the Polar sleep report when it arrives, but the
  user should not have to wait for it before seeing a provisional read.
- Provisional sleep onset should use the calibrated raw PPI/HR signal:
  - start after the user bedtime marker
  - look for a sustained 15-20 minute calm block
  - require HR to drop by roughly 3 bpm from the first-hour bed baseline
  - require low movement and acceptable PPI quality
- Provisional wake should prefer the user `I'm awake` marker when available.
  Current H10/Sleep2 calibration suggests backdating that marker by about
  3-5 minutes is closer to the physiological wake label than using it raw.
- Sensor-only wake detection is weak for this use case because quiet wake can
  remain physiologically sleep-like. HR/PPI/activity wake signals should be
  treated as confidence/context, not the primary wake fallback.
- If no wake marker exists, the first successful morning app-open/sync can be
  used as a lower-confidence wake proxy and labelled clearly.

## Morning Polling

- The Loop/Polar 360 SDK path is pull-based. Do not assume the device can push
  threshold alerts such as `HR > X` while Lodestone is disconnected.
- If the user does not press `I'm awake`, does not open the app, and no scheduled
  morning poll runs, Lodestone has no fresh data and should not pretend it has a
  morning prediction.
- A reasonable default morning polling window is every 20-30 minutes during a
  user-configured likely wake window.
- Morning polling is primarily for data availability and notifications:
  - pull recent PPI/HR if the user forgets the wake button
  - emit `morning read pending/ready` notifications
  - retry final sleep report checks at low cadence

## Daytime Warning

- Default daytime monitoring should be conservative periodic polling, not
  always-on streaming.
- A reasonable passive polling interval is 20-30 minutes. This supports trend
  and pacing feedback, but not guaranteed real-time safety alerts.
- Active monitoring remains useful for known-risk situations:
  - shower
  - cooking
  - leaving the house
  - lean/POTS-style test
  - any user-flagged high-risk block
- Active monitoring should be explicit, time-limited, and backed by a foreground
  service with a persistent notification.
- User-facing wording should describe background polling as pacing feedback or
  early caution, not as a real-time guardian alarm.

