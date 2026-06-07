# Lodestone Project Context

This is a compact living context snapshot for collaborators and future agents.
It is guidance, not a source of truth over current code or explicit user
instructions.

For the modelling line of thought, read:

- `docs/lexicon.md`
- `docs/current-thesis-and-measurement-strategy.md`
- `docs/journal-v2-current-state-contract.md`
- `docs/codex-handover.md`

## Product Shape

Lodestone is an Android-first personal health-monitoring prototype for ME/CFS
pacing, recovery tracking, and current-condition reflection.

The app is trying to explain subjective function and estimate safe objective
function, not simply produce a morning sleep-based readiness score.

The core question is:

> Given the latest available evidence, what can Lodestone responsibly say about
> why the user feels this way and how cautiously they should approach what comes
> next?

It remains a research prototype, not a medical device or validated health
product.

## Current Daily Flow

Keep the normal flow low friction:

- `Check in` to sync and update the current-state/planning read.
- Optional bedtime/wake markers when useful as annotations, not obligations.
- Candidate review only when evidence repair is useful.
- Journal with one-tap outcome save as the minimum valid entry.
- Optional imports from FoodLog and Grip Recorder.
- History for patterns, completeness, and paired signal/outcome review.
- Settings for device, Flow handoff, folders, calibration, and diagnostics.

Do not make the user audit sleep windows, wait for final vendor sleep reports,
or maintain a complex tag taxonomy as normal daily homework.

## Current Model Frame

Use these conceptual lanes:

- **Subjective function (SF):** lived state and day outcome. This remains
  primary because Lodestone exists to explain and support the user's experience.
- **Perceived function (PF):** the user's own capacity estimate, if explicitly
  captured. PF is user-perceived capacity, not Lodestone's derived forecast. It
  may be momentary or retrospective, but current journal outcome reports do not
  automatically create a PF lane.
- **Objective function (OF):** what the user can actually produce or tolerate
  now. Keep OF, stability/brittleness, and PEM/payback risk as related but
  separate planning concepts rather than one simple score.
- **Autonomic lane:** PPI/HRV/HR context for strain, possible recovery
  conditions, and recovery momentum.
- **Functional lane:** recent outcomes, PEM/payback, day shape, major tasks,
  grip sessions, and future objective probes.
- **Planning state/current-state read:** Lodestone's derived guidance from SF
  history, any explicit PF field if one exists, autonomic data, sleep/rest
  evidence, grip, context, and history.

The current thesis is delayed and asymmetric recovery:

- downgrades can happen quickly
- recovery takes longer than exertion
- good autonomic state may permit recovery but does not prove OF has recovered
- functional inertia should keep planning cautious until later functional
  evidence improves
- exertion and other drains matter as much as recovery signals, but high-friction
  exertion journaling is unlikely to be sustainable

## Data Sources

Active:

- Polar Loop / Polar 360-class device through the Polar BLE SDK
- `PPI_247` as the key raw autonomic lane
- `HR_247`, Nightly Recharge, sleep/rest provenance, skin temperature, daily
  summaries, and activity context as supporting lanes
- Journal V2 outcome and day-shape chips
- FoodLog CSV imports and weight rows
- Grip Recorder CSV imports

Calibration / secondary:

- H10/Sleep2/Health Connect remain calibration or analysis routes.
- Vendor sleep reports remain provenance/fallback/context, not the daily gate.

Archived:

- Garmin sidecar, Polar cloud backfill, offline recording, morning polling, and
  old morning-sync recovery notes live under `docs/archive/` and are not current
  product direction unless explicitly reopened.

Naming note:

- Some scripts, database fields, and old contracts still say `readiness`. Treat
  that as legacy naming for current-state/planning-state work unless a task is
  explicitly about renaming those identifiers.

Known live legacy identifiers:

- App code still has `MorningRead*`, `MorningPredictionSnapshot*`,
  `SleepEpisodeReadinessRules`, `isPrimaryForReadiness`, and
  `no_main_sleep`-style keys.
- Analysis scripts still include `readiness_outcome_report.py` and
  `old_readiness` comparison fields in `current_state_model_report.py`.
- Garmin helper scripts still exist as optional/calibration tooling even though
  Garmin is not an active modelling dependency.

Archived docs mean "not current product direction", not "all related code is
dead". Maintain live code when it is still used, and only remove or rename
legacy paths as deliberate migration work.

## Device And Flow Reality

- Polar Loop / Polar 360-class devices expose one practical BLE relationship at
  a time. Polar Flow can compete with Lodestone for the device.
- Lodestone should support graceful handoff, retry, and user messaging, but it
  should not assume seamless coexistence with Flow.
- This is active operational context. It is separate from the archived idea of
  waiting for final Loop sleep reports as the daily gate.

## Measurement Burden

The user's cognitive cost can be higher than physical cost. This matters.

Measurement channels should be judged by value and burden:

- Journal labels are primary but can be cognitively expensive.
- Grip is a direct but narrow physical OF probe and can affect later state.
- `check_2` means the low-friction daily grip protocol: two recorded
  repetitions from Grip Recorder.
- PVT-style cognitive probes may be worth testing outside Lodestone if short,
  dull, bounded, and skippable.
- Tetris-like probes are exploratory only because game score is confounded by
  practice, motivation, strategy, and retry pressure.
- Cognitive probes need timing context. Use probe/CSV timestamps plus
  Lodestone's sleep-window data to estimate time since likely waking. Distinguish
  sleepy, fatigued, fogged, and high-arousal states where possible; `tired` is
  too broad to explain much by itself. If the sleep/window evidence is
  ambiguous, mark derived timing as uncertain.
- Two weeks is enough to judge burden and obvious signal for a new probe, not
  enough to prove subtle utility.

Any probe can be skipped. Preserving the sustainable core channels is more
important than adding clever measurements.

## UI Direction

The app is moving toward a calmer daily-use UI:

- `Now` should be succinct and readable at a glance.
- Details belong lower down, in sheets, or collapsed sections.
- Evidence surfaces should list candidates and provenance without implying the
  user must repair them every day.
- Journal should remain small and simple.
- History can hold detailed per-day data, imports, and edits.
- Settings should keep sync/debug/calibration tools away from daily flow.

Avoid generic quantified-self dashboard drift. Every visible metric should earn
its place by helping pacing, recovery interpretation, or data repair.

## Engineering Notes

- Repository: `https://github.com/betterlucky/Lodestone.git`
- Local checkout: `<local path to your clone>`
- Kotlin, Jetpack Compose, Room, WorkManager, Polar BLE SDK.
- App targets Android API 35 and currently requires API 33+.
- Current DB includes grip session/repetition tables and import flow.
- Food and grip recorder apps own measurement/capture UX; Lodestone owns
  persistence and interpretation after CSV import.

## Data Safety

Never commit:

- `.env` files or API tokens
- Polar cloud credentials
- Garmin credentials/sessions
- raw pulled phone databases
- personal health exports
- calibration screenshots or labels
- large personal sample JSON

Use `/tmp` or `/private/tmp` for transient DB pulls, plots, and analysis
artifacts unless the user asks to preserve them.

## Agent Workflow

- Read `docs/agent-playbook.md` before substantial work.
- Use current docs before archived docs.
- Treat local model output as advisory only.
- Main agent should keep ownership of health model design, BLE/SDK
  interpretation, database safety, final patch integration, and verification.
