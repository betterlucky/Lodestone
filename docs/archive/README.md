# Archived Context

These notes are preserved for archaeology, not for current product direction.

Do not use archived docs as implementation guidance unless the user explicitly
reopens that topic. Prefer:

- `docs/current-thesis-and-measurement-strategy.md`
- `docs/journal-v2-current-state-contract.md`
- `docs/lodestone-redesign-architecture.md`
- `docs/candidate-review-contract.md`
- `docs/codex-handover.md`

Archive status is a strategy signal, not a deletion signal. Some scripts or app
paths may still reference archived concepts for optional analysis, calibration,
comparison, or legacy compatibility. Maintain live code when needed, but do not
let archived docs define new product direction unless the user deliberately
reopens that topic.

## Archived Notes

| File | Why archived |
| --- | --- |
| `garmin-sidecar-plan.md` | Garmin is no longer part of the active modelling path. Keep only as a deferred sidecar plan. |
| `polar-cloud-backfill-probe.md` | Polar cloud probing is research-only and should not shape the daily app unless deliberately reopened. |
| `offline-recording-archive.md` | Offline recording was a one-off investigation superseded by normal `PPI_247` sync. |
| `morning-sync-recovery-plan.md` | Captures an older morning-core sync recovery slice. Useful implementation history, not current product framing. |
| `polling-and-monitoring-decisions.md` | Morning polling/readiness assumptions are stale under the current-state and measurement-burden thesis. |

## Current Boundary

The live project should not assume:

- the user waits for a final Loop sleep report before Lodestone can be useful
- Garmin is an active data dependency
- Polar cloud backfill is part of the product architecture
- offline recording should return to the daily workflow
- a standard morning sleep session is the central modelling anchor

Those topics can be reopened, but only as deliberate new work with the current
thesis in mind.
