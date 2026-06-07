# Offline Recording Archive

This document preserves the results of the one-off Polar Loop / Polar 360 offline-recording investigation. The live Lodestone app no longer uses this workflow because normal `PPI_247` sync now provides the raw overnight PPI lane we were trying to recover.

## What We Learned

- SDK mode was not suitable for the product workflow because it could interfere with normal device behavior and did not expose the desired sleep-derived data path.
- Normal-mode offline PPI recording did work, including overnight-scale recordings, but it added user friction and duplicated data that is now available through `PPI_247`.
- Offline PPG was possible enough to test, but the files were large and the value was lower once direct PPI became available.
- Training-session experiments were validation-only and did not become a reliable path for overnight HRV.
- Polar Flow still appears useful for finalising sleep-derived reports, but Flow is now treated as a user-controlled fallback rather than part of the raw PPI capture path.

## Archived Data

Before removing the live offline tables, a private phone database archive was pulled to:

`/private/tmp/HealthMonitorArchives/lodestone-db-20260429-141446`

That archive contains personal health data and must not be committed or shared.

## If This Needs Rebuilding

The historical approach was:

1. Connect to the Loop through Lodestone.
2. Start normal-mode offline PPI recording via the Polar BLE SDK.
3. Later stop recording, list regular and split offline recordings, fetch candidates created after the validation run start time, and derive 5-minute PPI/RMSSD epochs.
4. Run normal Polar sync afterward to compare sleep, Nightly Recharge, `PPI_247`, skin temperature, daily summary, and activity samples.

If this is ever revived, rebuild it as a separate validation module rather than restoring it to the main morning workflow.
