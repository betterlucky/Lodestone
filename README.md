# Lodestone

Lodestone is an Android-first personal health-monitoring prototype for pacing,
recovery tracking, and morning/evening reflection. It is currently a research
project rather than a polished consumer app: the goal is to find out which
wearable signals are genuinely useful before pretending we have a finished
model.

The project grew out of a practical question: can a relatively simple wearable
workflow provide an early, interpretable read on how cautiously to approach the
day, especially when vendor apps expose scores but hide much of the underlying
physiology?

## What It Does Today

- Syncs directly with a Polar Loop / Polar 360-class device over the Polar BLE
  SDK.
- Stores local raw and derived lanes for sleep, Nightly Recharge, `PPI_247`,
  `HR_247`, skin temperature, daily summaries, and activity context.
- Produces an interim morning signal from raw PPI plus user sleep/wake markers,
  then a confirmed morning read once the final Polar sleep report is available.
- Shows an overnight HRV trajectory view with raw RMSSD, rolling-median, and
  linear-trend overlays.
- Captures daily review labels such as evening outcome, approach to the day,
  notes, and muscle weakness.
- Imports food-log CSVs and weight rows from a separate food-logging workflow.
- Includes analysis helpers for sleep-window calibration, completeness checks,
  Garmin sidecar comparison, and research-only Polar cloud probing.

## Current Research Findings

These are project findings, not general medical claims:

- Polar's normal `PPI_247` feed can provide the raw overnight autonomic lane we
  originally thought was missing, but device timing and sync behaviour need to
  be handled carefully.
- Vendor sleep finalisation can lag wake time, so a useful morning UX should not
  require waiting for the final sleep report before showing anything.
- A provisional sleep window based on bedtime/wake markers plus a sustained
  low-motion HR drop appears promising enough to use while the final report is
  pending.
- Polar Flow is still useful for firmware and sleep-report workflows, but it
  competes for the Loop's single BLE connection. Lodestone therefore treats Flow
  as an explicit handoff rather than something that can seamlessly coexist in
  the background.
- Garmin remains useful as a comparison/sidecar source, but its sleep handling
  and browser-based data extraction have both been brittle for this use case.

More detail lives in:

- [`LodestoneContext.md`](LodestoneContext.md)
- [`docs/polling-and-monitoring-decisions.md`](docs/polling-and-monitoring-decisions.md)
- [`docs/offline-recording-archive.md`](docs/offline-recording-archive.md)
- [`docs/polar-cloud-backfill-probe.md`](docs/polar-cloud-backfill-probe.md)

## Daily Workflow

The intended low-friction flow is:

1. Mark bedtime with `I'm going to bed`.
2. On waking, tap `I'm awake` to record wake time and run the morning sync.
3. Use the provisional or confirmed morning signal as a pacing prompt, not as a
   diagnosis.
4. At day's end, record the subjective outcome and optional context.

The evening labels are deliberately important. The model is still being trained
against lived outcomes rather than treated as proven because a graph looked
convincing once.

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

- collecting enough paired morning-prediction/evening-outcome data to judge the
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
