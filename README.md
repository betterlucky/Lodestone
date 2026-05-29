# Lodestone

Lodestone is an Android-first personal health-monitoring prototype for pacing,
recovery tracking, and current-condition reflection. It is currently a research
project rather than a polished consumer app: the goal is to find out which
wearable signals are genuinely useful before pretending we have a finished
model.

The project grew out of a practical question: can a relatively simple wearable
workflow provide an early, interpretable read on how cautiously to approach what
comes next, especially when vendor apps expose scores but hide much of the
underlying physiology?

## What It Does Today

- Syncs directly with a Polar Loop / Polar 360-class device over the Polar BLE
  SDK.
- Stores local raw and derived lanes for sleep/rest episodes, Nightly Recharge,
  `PPI_247`, `HR_247`, skin temperature, daily summaries, and activity context.
- Produces a readiness signal from raw PPI plus manual or inferred sleep/rest
  windows, with Polar sleep reports kept as supporting context rather than the
  canonical gate.
- Lets the user review inferred sleep/rest candidates from Check in or Catch up,
  accept/edit/dismiss them, mark rest or no main sleep, and choose which
  confirmed window may drive readiness.
- Shows an overnight HRV trajectory view with raw RMSSD, rolling-median, and
  linear-trend overlays.
- Captures daily review labels such as evening outcome, approach to the day,
  notes, and muscle weakness.
- Imports food-log CSVs and weight rows from a separate food-logging workflow.
- Includes analysis helpers for sleep-window calibration, readiness/outcome
  validation, completeness checks, Garmin sidecar comparison, and research-only
  Polar cloud probing.

## Current Research Findings

These are project findings, not general medical claims:

- Polar's normal `PPI_247` feed can provide the raw overnight autonomic lane we
  originally thought was missing, but device timing and sync behaviour need to
  be handled carefully.
- Vendor sleep finalisation can lag wake time, so a useful readiness UX should
  not require waiting for the final sleep report before showing anything.
- A provisional sleep/rest window based on markers plus sustained low-motion HR
  drop appears promising enough to propose candidates while the final report is
  pending.
- PPI-only candidates are suggestions until accepted or edited. Confirmed
  no-sleep days are represented directly instead of fabricating a sleep window.
- Polar Flow is still useful for firmware and sleep-report workflows, but it
  competes for the Loop's single BLE connection. Lodestone therefore treats Flow
  as an explicit handoff rather than something that can seamlessly coexist in
  the background.
- Garmin remains useful as a comparison/sidecar source, but its sleep handling
  and browser-based data extraction have both been brittle for this use case.

More detail lives in:

- [`LodestoneContext.md`](LodestoneContext.md)
- [`docs/codex-handover.md`](docs/codex-handover.md)
- [`docs/polling-and-monitoring-decisions.md`](docs/polling-and-monitoring-decisions.md)
- [`docs/offline-recording-archive.md`](docs/offline-recording-archive.md)
- [`docs/polar-cloud-backfill-probe.md`](docs/polar-cloud-backfill-probe.md)

## Daily Workflow

The intended low-friction flow is moving toward:

1. Run `Check in` to sync and assess the current situation without implying a
   sleep/wake event.
2. Record sleep/wake events with `I'm going to bed` and `I'm awake` when those
   explicit markers are useful.
3. Run `Catch up` when Lodestone detects stale syncs, missing markers, or
   unresolved sleep/rest candidates. The repair dialog groups missing dates,
   shows candidates, confirmed/no-sleep decisions, saved evening reviews, and
   no-candidate days.
4. Review inferred windows when needed: use a window as main sleep, keep it as a
   nap/rest context row, edit the timing, dismiss it, or mark no main sleep.
5. Interpret the readiness/stability signal as a pacing prompt, not as a
   diagnosis.
6. At day's end, record the subjective outcome and optional context.

The evening labels are deliberately important. The model is still being trained
against lived outcomes rather than treated as proven because a graph looked
convincing once.

For local calibration, `scripts/readiness_outcome_report.py --health-db <db>`
compares morning readiness snapshots with saved evening outcomes, day-to-day
stability, coarse next-day payback context, and sleep-episode edge cases such as
no-sleep nights, delayed timings, naps, and empty days. Its output is
intentionally descriptive; it should not be used as a precise load-budget claim.

For data-lane checks, `scripts/daily_data_completeness.py --health-db <db>
--start-date <yyyy-mm-dd> --end-date <yyyy-mm-dd>` reports raw and derived lane
counts, plus sync-profile diagnostics so FULL-only lanes and pruned raw records
are not mistaken for missing data.

## Tech Stack

- Kotlin
- Jetpack Compose
- Room
- WorkManager
- Polar BLE SDK
- Health Connect support for analysis experiments
- Python and shell helper scripts for local research workflows

The app currently targets Android API 35 and requires API 33 or later.

## Building

Requirements:

- Android Studio or a compatible Gradle/JDK setup
- JDK 21
- An Android device for the BLE workflow

Build from the repository root:

```bash
./gradlew :app:assembleDebug
```

Install on a connected device:

```bash
./gradlew :app:installDebug
```

The repository currently includes the Polar BLE SDK AAR used by the app in
`app/libs/`.

## Project Status

Lodestone is usable as a personal prototype, but it is not yet a general-release
health product.

Still in progress:

- collecting enough paired readiness/evening-outcome data to judge the
  model honestly
- deciding which food, weight, and context variables are genuinely useful
- refining the daily UX now that the exploratory probe phase is mostly over
- determining how much of the current Flow handoff can be made graceful for
  anyone beyond a patient power user

## Data And Privacy

The app is local-first. Personal databases, credentials, Polar cloud exports,
Garmin sessions, and calibration captures are intentionally ignored by git.

Do not commit:

- `.env` files or API tokens
- pulled phone databases
- raw health exports
- Garmin sessions/browser profiles
- calibration screenshots or labels

If you fork this project for your own data, inspect `.gitignore` before adding
new tools. Health projects become privacy projects surprisingly quickly.

## Safety

Lodestone is an experimental self-tracking tool. It is not a medical device, it
does not diagnose illness, and its traffic-light outputs should be treated as
decision support for reflection and pacing rather than clinical advice.

## License

Lodestone is source available under the
[PolyForm Noncommercial License 1.0.0](LICENSE.md). You may use, modify, and
redistribute it for noncommercial purposes; commercial use requires separate
permission.

## Repository Map

- `app/` - Android application
- `docs/` - implementation notes and preserved research decisions
- `scripts/` - local analysis and data-support tools
- `LodestoneContext.md` - current project context snapshot for collaborators and
  future agents

## Contributing

This repo is public because the research and engineering may be useful to
others, but it is still primarily a working prototype. Issues and thoughtful
discussion are welcome; expect the architecture and model assumptions to keep
evolving while the evidence base grows.
