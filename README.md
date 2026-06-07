# Lodestone

Lodestone is an Android-first personal health-monitoring prototype for ME/CFS
pacing, recovery tracking, and current-condition reflection.

The project is not trying to be a generic quantified-self dashboard. Its current
aim is narrower and more practical: help the user understand why they feel as
they do, and estimate how cautiously they should approach what comes next.

Lodestone is a research prototype, not a medical device or consumer-ready health
product.

## Current Frame

The project has moved away from a simple "standard sleep night -> morning
readiness" model.

Current modelling terms:

- **Subjective function (SF):** the user's lived state and day outcome.
- **Perceived function (PF):** the user's own capacity estimate, if explicitly
  captured.
- **Objective function (OF):** what the user can actually produce or tolerate
  now.
- **Autonomic state:** HRV/PPI/HR context that may show strain or recovery
  conditions, but is not direct usable capacity.

Functional planning should consider current OF, stability/brittleness, and
PEM/payback risk as related but separate concepts, not one blended score.

The working thesis is delayed and asymmetric recovery: exertion can be brief,
while recovery may take days. Good autonomic data may mean recovery conditions
are improving, but it should not automatically override recent PEM, poor
function, or low objective-function probes.

PF and SF are still evolving modelling concepts. End-of-day journal outcome
reports are SF by default. PF means user-perceived capacity only when the app
explicitly captures a capacity estimate; ordinary outcome labels do not create
a PF lane. The app's forecast should be called the planning state or
current-state read.

Some internal scripts, database fields, and older contracts still use
`readiness` as a legacy name. In current project language, read that as
current-state or planning-state unless a task is explicitly about renaming
identifiers.

More detail:

- [`docs/lexicon.md`](docs/lexicon.md)
- [`docs/current-thesis-and-measurement-strategy.md`](docs/current-thesis-and-measurement-strategy.md)
- [`docs/journal-v2-current-state-contract.md`](docs/journal-v2-current-state-contract.md)
- [`docs/lodestone-redesign-architecture.md`](docs/lodestone-redesign-architecture.md)

## What It Does Today

- Syncs directly with a Polar Loop / Polar 360-class device over the Polar BLE
  SDK.
- Stores local raw and derived lanes for sleep/rest episodes, Nightly Recharge,
  `PPI_247`, `HR_247`, skin temperature, daily summaries, and activity context.
- Produces a current-state/planning signal from autonomic and functional lanes.
- Treats sleep/rest windows as evidence and provenance, not as the sole anchor.
- Keeps Polar sleep reports as supporting context/fallback rather than the
  primary daily gate.
- Lets the user review inferred sleep/rest candidates from Check in or Catch up,
  accept/edit/dismiss them, mark rest, record no-sleep/no-primary-window
  decisions, and choose explicit primary windows when needed.
- Captures low-friction Journal labels: evening outcome, approach to the day,
  day-shape chips, PEM/payback markers, notes, and related context.
- Imports food-log CSVs and weight rows from a separate FoodLog workflow.
- Imports grip-strength sessions and repetitions from a separate Grip Recorder
  workflow.
- Includes local analysis helpers for paired signal/outcome review,
  completeness checks, and calibration experiments.

## Daily Workflow

The intended low-friction flow is:

1. Run `Check in` to sync and assess the current situation.
2. Optionally record bedtime/wake markers when they are useful annotations.
3. Review sleep/rest candidates only when repair or curiosity makes it useful.
4. Import FoodLog or Grip Recorder CSVs when available.
5. Interpret the current-state/planning signal as pacing support, not a verdict.
6. At day's end, save the simplest useful Journal outcome.

One-tap Journal save remains valid. Optional measurements should be skippable
without guilt; preserving sustainable core channels matters more than collecting
every interesting signal.

## Measurement Strategy

Every signal has a burden:

- HRV/PPI is mostly passive but indirect.
- Journal labels are primary but cognitively expensive.
- Grip is a direct physical OF probe, but it is narrow and can affect later
  state.
- `check_2` means the low-friction daily grip protocol: two recorded
  repetitions from Grip Recorder.
- Grip and FoodLog imports are integrated data lanes for now. Food logging may
  be on hiatus without changing the import boundary.
- Future PVT/Tetris-like cognitive probes should remain outside-Lodestone trials
  until sustainability and obvious explanatory value are plausible.

Two weeks of a new probe is enough to judge burden and obvious signal. It is not
enough to prove subtle effects, and subtle effects may not justify integration.

The model should learn from disagreements between lanes rather than hiding them.
For example, good autonomic state plus low grip may mean recovery conditions are
improving while usable function has not recovered yet.

## Active Documentation

- [`LodestoneContext.md`](LodestoneContext.md) - compact current project context.
- [`docs/codex-handover.md`](docs/codex-handover.md) - short future-agent
  handover.
- [`docs/lexicon.md`](docs/lexicon.md) - canonical project language for current,
  reserved, legacy, and experimental concepts.
- [`docs/current-thesis-and-measurement-strategy.md`](docs/current-thesis-and-measurement-strategy.md) -
  current modelling and measurement anchor.
- [`docs/journal-v2-current-state-contract.md`](docs/journal-v2-current-state-contract.md) -
  Journal and functional/current-state contract.
- [`docs/lodestone-redesign-architecture.md`](docs/lodestone-redesign-architecture.md) -
  current UI direction.
- [`docs/candidate-review-contract.md`](docs/candidate-review-contract.md) -
  sleep/rest candidate repair contract.
- [`docs/archive/README.md`](docs/archive/README.md) - archived historic context
  that is no longer active direction.

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

The repository includes the Polar BLE SDK AAR used by the app in `app/libs/`.
That SDK is owned and licensed separately by Polar Electro Oy; see
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

## Project Status

Lodestone is usable as a personal prototype, but it is not yet a
general-release health product.

Still in progress:

- collecting enough paired SF/outcome, autonomic, grip, and day-shape data to
  judge the model honestly
- refining the UI around current-state and low-friction daily use
- understanding whether grip is sustainable and how it maps to broader OF
- deciding whether any cognitive probe can add value without overloading the
  user
- determining how much of the Flow handoff can be made graceful beyond a
  patient power-user setup, given that Flow and Lodestone can compete for the
  same device BLE connection

## Data And Privacy

The app is local-first. Personal databases, credentials, Polar cloud exports,
Garmin sessions, calibration captures, and health exports are intentionally
ignored by git.

Do not commit:

- `.env` files or API tokens
- pulled phone databases
- raw health exports
- Garmin sessions/browser profiles
- calibration screenshots or labels

If you fork this project for your own data, inspect `.gitignore` before adding
new tools. Health projects become privacy projects surprisingly quickly.

## Safety

Lodestone is an experimental self-tracking tool. It does not diagnose illness,
does not prescribe treatment, and its outputs should be treated as decision
support for reflection and pacing rather than clinical advice.

## License

Lodestone's own source code, documentation, and scripts are licensed under
[GPL-3.0-or-later with an additional Polar BLE SDK linking exception](LICENSE.md).

The linking exception permits Lodestone to be combined with the Polar BLE SDK,
which has its own separate license. It does not relicense Polar's SDK or remove
the need to comply with Polar's terms and notices.

## Repository Map

- `app/` - Android application
- `docs/` - current implementation notes plus archived research context
- `scripts/` - local analysis and data-support tools
- `LodestoneContext.md` - current project context snapshot for collaborators and
  future agents

## Contributing

This repo is public because the research and engineering may be useful to
others, but it is still primarily a working prototype. Issues and thoughtful
discussion are welcome; expect the architecture and model assumptions to keep
evolving while the evidence base grows.
