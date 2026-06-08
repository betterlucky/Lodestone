# Codex Handover

This is the short current-context note for future Lodestone chats. It is not an
exhaustive history. Prefer current code, explicit user instructions, and
`docs/current-thesis-and-measurement-strategy.md` when anything conflicts.

## Read First

- `docs/agent-playbook.md` before substantial work.
- `docs/lexicon.md` for canonical project language before changing model,
  journal, sleep/rest, or UI wording.
- `docs/current-thesis-and-measurement-strategy.md` for the current modelling
  and measurement strategy.
- `docs/exertional-load-analysis.md` for the exploratory EL report, current EL
  findings, and why EL remains analysis-layer evidence rather than a production
  status input.
- `docs/journal-v2-current-state-contract.md` for the functional lane,
  low-friction journal, day-shape chips, PEM/payback handling, and current-state
  model split.
- `docs/lodestone-redesign-architecture.md` for the current UI direction.
- `docs/candidate-review-contract.md` only when touching sleep/rest candidate
  review.
- `docs/archive/README.md` before consulting old Garmin, Polar cloud, offline
  recording, morning polling, or morning-sync notes.

## Current Project Shape

- Repository: `https://github.com/betterlucky/Lodestone.git`
- Local checkout: `<local path to your clone>`
- Product name: Lodestone
- Platform: Android-first Kotlin/Compose prototype
- Purpose: personal ME/CFS pacing, recovery tracking, and current-condition
  reflection.
- Status: research prototype, not a medical device or validated health product.
- Privacy posture: local-first. Treat health exports, phone DB pulls, Polar and
  Garmin credentials, cloud data, and calibration captures as sensitive.

## Current Thesis

Lodestone is no longer primarily a "normal sleep night -> morning readiness"
app. The current frame is:

- explain subjective function (SF): why the user feels as they do
- estimate current objective function (OF): what they can try now
- keep OF, stability/brittleness, and PEM/payback risk related but separate
  rather than blending them into one score
- acknowledge that perceived function (PF) is valid but ambiguous when the user
  explicitly reports capacity; PF is not Lodestone's derived forecast
- treat HRV/PPI/autonomic state as recovery context, not direct usable capacity
- account for delayed/asymmetric recovery and functional inertia
- account for exertion and other drains, while avoiding high-friction exertion
  journaling
- keep sleep/rest windows as evidence/provenance, not the central throne

Good autonomic data may mean conditions are better for recovery. It should not
erase recent PEM, poor function, or low objective-function probes by itself.

## Active Data Lanes

- **Autonomic lane:** `PPI_247`, RMSSD distributions/trajectory, HR, Nightly
  Recharge, sleep/rest provenance, and supporting context.
- **Functional lane:** subjective outcome, PEM/payback markers, day-shape chips,
  major-task context, mostly-horizontal/left-house anchors, grip sessions, and
  future objective-function probes.
- **Exertional load:** activity and reported load context. Use
  `scripts/exertional_load_report.py` to explore EL -> later SF/PEM and
  state -> actual activity; do not treat EL as OF or a load budget yet.
- **Planning state:** conservative user-facing guidance combining the lanes.

Functional inertia is the current planning anchor. Recovery should climb slowly
unless later functional evidence supports improvement.

End-of-day journal outcome reports are SF by default. The app's derived forecast
is `planning state` or `current-state read`, not PF. Treat PF as reserved unless
the UI explicitly captures a user capacity estimate.

Naming note: some scripts, database fields, and older contracts still say
`readiness`. Treat that as legacy naming for current-state or planning-state
work unless the task is explicitly to rename identifiers.

## Measurement Burden

Every measurement channel has cost:

- HRV/PPI is mostly passive but indirect.
- Journal/SF labels are primary but cognitively expensive.
- Grip is a direct physical OF probe but can cause local/systemic measurement
  cost.
- `check_2` means the low-friction daily grip protocol: two recorded
  repetitions from Grip Recorder.
- PVT/Tetris-like cognitive probes are exploratory outside-Lodestone trials
  until they show acceptable burden and obvious value. Treat them as
  interventions as well as measurements.

Do not build an impressive data stack that the user cannot sustain when unwell.

## Current Workflow Direction

- Keep daily use low friction: Check in, optional markers, optional imports,
  and one-tap Journal save.
- User-selected date matters for review/import/reset behavior.
- Grip Recorder owns timed grip measurement UX and disposable CSV export.
  Lodestone imports sessions and owns persistence/interpretation.
- FoodLog owns food capture. Lodestone imports daily CSVs and weight rows.
- Sleep/rest candidate review remains a repair/evidence surface, not required
  homework.
- H10/Sleep2/Health Connect are calibration routes, not normal daily workflow.
- Polar Flow can compete with Lodestone for the device BLE connection. Keep
  handoff, retry, and user messaging practical, but do not treat Flow sleep
  reports as the daily gate.

## Archived Or Stale Topics

The following are not current direction unless explicitly reopened:

- waiting for final Loop sleep reports as the main daily gate
- Garmin as an active modelling dependency
- Polar cloud backfill as product architecture
- offline recording as normal PPI capture
- morning polling/readiness as the main app frame

Those notes live under `docs/archive/`.

## Safety Notes

- Never commit `.env`, Polar cloud tokens, Garmin credentials/sessions, raw
  phone DB pulls, health exports, calibration screenshots, or large personal
  sample JSON.
- Use `/tmp` or `/private/tmp` for transient DB pulls, plots, and analysis
  artifacts unless the user asks to preserve them.
- Be careful before DB resets, migrations, or cleanup scripts. The user has
  explicitly said accidental data loss would be bad.
- Local-model sidecars are advisory only. Do not pass secrets or personal raw
  health payloads to them.

## Near-Term Strategic Work

- Finish UI polish around the current-state framing.
- Keep collecting paired SF/outcome, autonomic, grip, and day-shape data.
- Watch whether `check_2` grip is sustainable and whether it reflects local
  recovery, systemic state, or both.
- Try cognitive probes only if they remain low burden and do not threaten the
  core data channels. Two weeks is enough to judge sustainability and obvious
  signal, not subtle long-term utility.
