# Lodestone Redesign Architecture

This note is the product and implementation contract for moving Lodestone from
the prototype `Device / Today / Review` structure toward a calmer daily-use app.
It should guide the redesign tasks under Kanban parent `#256` and its dependent
implementation cards.

Related contracts and background:

- `docs/current-thesis-and-measurement-strategy.md` defines the current
  subjective-function/objective-function framing and measurement-burden rules.
- `docs/candidate-review-contract.md` defines the existing sleep/rest candidate
  data and action contract.
- `docs/hrv-autonomic-detail-scopes-contract.md` defines the Signals/HRV detail
  direction that supersedes the first-pass evidence-chip drift on `Now`.
- `docs/journal-v2-current-state-contract.md` defines the stepped Journal
  capture flow that supersedes the first-pass full Journal tab.
- `docs/codex-handover.md` preserves older project context and safety notes.
- `docs/archive/README.md` lists older sync, Garmin, Polar cloud, and offline
  recording notes that are no longer active direction.

The core product shift is from "start of day readiness" to "use the latest
available evidence to explain subjective state and support pacing decisions."
The UI should support non-standard sleep patterns without turning sleep-window
repair into required daily homework. It should also make room for objective
function probes, such as grip or future cognitive checks, without overwhelming
the daily flow.

## Navigation

The current target app shell is:

| Surface | Purpose |
| --- | --- |
| `Now` | Daily Forecast, stability/payback context when available, recent sleep/rest summary, freshness, check-in, and low-friction Journal capture. |
| `Signals` | Autonomic detail, HRV scopes, sleep/rest episodes and repair, data completeness, PPI freshness, and other live evidence detail. |
| `History` | Past days, paired prediction/outcome reporting, data completeness, stability, per-day detail, and Journal edit/create for selected dates. |
| `Settings` | Device, controlled Flow maintenance, daily-flow preferences, calibration imports, sync windows, and diagnostics. |

`Settings` should be opened from a gear or sheet, not be a primary daily tab.
Device connection detail should move out of the main navigation. The `Now`
screen can show a compact Loop status pill, but scan/connect/capability tooling
belongs in Settings.

The separate primary `Journal` tab is retired in the current target shape. The
same capture capability remains, but it is launched from `Now` for today's
low-friction entry and from `History` when editing or backfilling a selected
date. If an intermediate implementation keeps a Journal tab temporarily, that is
a migration step rather than the desired information architecture.

## Regular Flow

The normal happy path is:

1. Open `Now`.
2. Tap `Check in`.
3. Read the capacity forecast, stability/payback context when available,
   recent sleep/rest total, and freshness.
4. Later, when the app shifts focus or the user chooses it, answer the first
   Journal prompt and tap `Done`. Optional questions remain opt-in.

Normal use must not require reviewing sleep windows, confirming candidates, or
pressing a wake marker. Those controls remain available, but they are secondary
unless the app cannot produce a usable read or a source disagreement is
material enough to call out.

This is the main design boundary: the model may choose and display an analysis
window, but the user should not feel they must audit that choice every day.

## Hero And Adaptive Focus

The `Now` hero is the app's primary daily surface. A user should usually be able
to open the app, understand the current read, perform at most one obvious
action, and leave.

The default hero order is:

1. derived capacity forecast
2. stability, when supported
3. PEM/payback risk, when supported
4. recent sleep/rest summary
5. last sync/check-in timestamp
6. confidence or robustness only when it changes interpretation, is degraded, or
   the read needs caveating

`Planning State` and `Current-State Read` are internal model terms. They should
not be the top-level user-facing hero label. Use `Daily Forecast` as the hero
label: it is familiar, implies uncertainty without apology, and keeps the app
approachable while the supporting copy carries the pacing purpose.

The top-level read estimates capacity. It should not present SF as if SF were
the forecast: SF is user-reported outcome/history and may appear as supporting
context, confirmation, or recovery-inertia evidence. OF is the practical target,
but Lodestone estimates it rather than observing it directly. If stability or
payback risk are shown separately, each may need its own confidence treatment.

The hero should mention recent sleep/rest in plain language, such as
`Recent rest: 6h 40m (last 18h)`. It does not need to name the source at top
level. Source, window choice, disagreement, and quality details belong in
`Signals` unless they materially change confidence or make the read unusable. A
qualitative sleep summary, such as low/neutral/good, can live in Signals rather
than competing with the capacity forecast.

`Now` is a status page, not an evidence dashboard. HRV, RMSSD, PPI receipts,
autonomic labels, trajectory charts, evidence chips, full provenance, sleep/rest
repair, and data-quality breakdowns belong in `Signals` or `History`. The model
may still use autonomic data; the top-level surface should show the pacing
forecast and only the context needed to act on it.

The app should adapt its focus across the day. At the start of a cycle, `Now`
should foreground the forecast/check-in flow. Later in the cycle, the default
focus may shift toward Journal capture. This is a focus shift, not a navigation
trap: tabs and manual navigation remain available.

Journal focus timing should have two modes:

- `Auto from wake`: default. Shift toward Journal around six hours after the
  selected wake estimate for the active read. The selected wake estimate may
  come from the model-selected sleep/rest window, a marker, or the active source
  for the current session. If no usable wake estimate is available within 12
  hours, fall back to 18:00 local time.
- `Fixed time`: use the user's chosen local time as the primary Journal focus
  gate, regardless of wake estimate. The time picker can default to 18:00, but
  the explicit mode toggle is what makes it a fixed-time preference.

## Current-State Language

Separate these concepts in UI state and copy:

| Concept | Meaning |
| --- | --- |
| Current state | The derived capacity forecast or pacing status Lodestone can currently infer. |
| Signal robustness | Whether the read is well supported by data coverage, quality, freshness, and baseline history. |
| State stability | Whether the current inferred state appears steady, mixed, improving, or degrading. |
| Freshness | How recently relevant data and sync attempts were updated. |
| Provenance | Which sleep/rest/no-sleep source or model estimate the current read used. |

Avoid presenting a brittle or partial read as a confident verdict. Missing data
should be explicit, but not alarming when it is expected or not applicable.

## Check-In And Marker Intents

Check-in and marker capture are separate concepts. `Check in` syncs data and
updates the current read where possible. Bedtime and waking markers are
annotations/calibration aids. The UI should not require a marker action to sync,
and it should not require sync to save a marker.

Supported marker visibility modes:

| Mode | Front-page actions |
| --- | --- |
| `No markers` | `Check in` only. |
| `Bedtime` | `Check in` and `Bedtime marker`. |
| `Bedtime + waking` | `Check in`, `Bedtime marker`, and `Waking marker`. |

The mode controls which marker actions appear on `Now`; it must not change
whether the user can open the evidence sheet and edit markers/windows manually.

Intent reset rules:

- Default to `Info` on app open or resume.
- Reset to `Info` after any action.
- Reset to `Info` after a short inactivity timeout.
- If an action fails, make it clear whether the marker was saved and whether
  the current read was refreshed.

Do not force a `Waking marker` action on users who selected `Bedtime` mode.
Bedtime-only use is valid: the bedtime marker is primarily a sleep-latency and
provenance note.

Manual wake remains useful for users who want explicit bookends, but it is not
part of the normal morning sync requirement.

## Marker States

Markers are annotations and calibration aids, not obligations.

For `Bedtime` mode:

- After `Bedtime marker`, show a short confirmation such as
  `Bedtime marker saved: 01:42`.
- After a brief cooldown, collapse it to a quieter line such as
  `Last bedtime marker: 01:42`.
- Treat the bedtime marker as active only while the following sleep episode is
  unresolved. Once a later model-generated, manually edited, or Loop sleep
  window is established, the bedtime marker becomes latency/provenance evidence
  for that window rather than an active "still asleep" state.
- If no resolving sleep window appears for a long period because the phone was
  off, data was not collected, or sync was missed, age the marker into stale
  context instead of keeping the day blocked.
- A later plain `Check in` should work normally, even if the user checks the
  phone hours after waking.
- Do not show a missing-wake warning in bedtime-only mode.

For `Bedtime + waking` mode:

- A bedtime marker can show a stronger active state, but it must still age
  gracefully.
- If waking is missed, show `Bedtime saved; waking not recorded` or similar.
- Normal `Check in` remains available and should use model/vendor/manual
  evidence as available.
- Old bedtime markers should become stale context, not a blocking "still
  asleep" state.

For `No markers` mode:

- Hide front-page bedtime and waking actions.
- Do not show marker-missing warnings or prompts.
- Continue to show marker/window evidence in the evidence sheet when it exists
  from previous modes, imports, calibration routes, or manual edits.

All marker lines should be editable from the evidence sheet. The front page may
also expose edit/clear actions when there is enough room and the marker is
currently relevant.

## Time Editing

Use clear 24-hour time pickers for marker and window edits. Do not use a radial
12-hour clock where 13-24 are hidden behind 1-12.

Required behavior:

- Hour selection must make `00` through `23` explicit.
- Date selection must be visible because sleep windows often cross midnight.
- Window editors must show a live duration preview.
- Useful quick actions may include `Now`, `15m ago`, `30m ago`, and detected
  estimate shortcuts.
- End-before-start validation should be clear and kind.
- Suspiciously long windows should warn without trapping the user unnecessarily.

The existing free-text `yyyy-MM-dd HH:mm` edit path may remain as a fallback or
debug affordance, but it should not be the normal editing experience.

## Sleep And Window Evidence

Sleep-window review is a secondary evidence/provenance surface, not normal
daily homework. Its primary live home is `Signals`; retrospective review belongs
in `History`.

The `Signals` sleep/rest section should show available sources:

- model-estimated window
- Loop final sleep report
- manual bedtime marker
- manual wake marker
- edited user window
- PPI-inferred sleep/rest window candidates
- no-sleep or no-primary-window decision
- H10/Sleep2/Health Connect calibration evidence when present

The user can inspect, add, edit, override, or record a
no-sleep/no-primary-window decision from this surface. The app should keep
working when the user never opens it.

When multiple sources provide plausible sleep windows for the same date, keep
them visible as separate evidence rather than collapsing them too early. This is
important both for user correction and for later model checking. The default
active-window choice should be:

1. user-selected override, if present
2. model/auto-generated window, when available and usable
3. marker/manual-derived window
4. Loop final sleep report

The Loop report should still be visible as provenance/context when it is not the
active choice, especially when it disagrees with model or marker-derived
windows. Settings should avoid a separate preferred-source control until real
use shows that the marker mode cannot infer the right default.

Only interrupt or strongly surface window review when:

- no usable read can be produced without user input
- sources disagree enough to materially change status or robustness
- the user explicitly opens catch-up/history repair
- a previous user-selected primary window needs attention

## Active Analysis Window

The implementation should expose one active analysis-window provenance object
for the current read. Status display, Signals HRV graphing, provenance copy, and
history rows should all reference the same provenance.

The active provenance should identify, where available:

- source date
- start and end time
- source type, such as model estimate, edited user window, Loop report,
  marker-derived window, no-sleep/no-primary-window, or pending
- confidence or robustness label
- whether it was user-selected or model-selected
- short reason why it was used

The active window does not have to be user-confirmed when the model can produce
a usable read. User confirmation is an override and audit tool, not a mandatory
daily step.

When the active provenance is model-selected, the UI should make that clear
without making it sound suspect by default. The tone is "used this estimate",
not "needs your approval".

## Journal

Journal capture replaces the old `Review` framing. It is a capture/edit flow,
not a primary navigation tab in the target information architecture.

The capture/edit flow should keep:

- evening outcome
- approach to day
- muscle weakness
- notes
- food import
- weight
- selected-date navigation

Journal should not require sleep-window review. It may show a compact current
or historical context summary for the selected date when useful, but the
subjective outcome is still the main label. The default capture path should be
the stepped flow from `docs/journal-v2-current-state-contract.md`: first answer
the outcome, then make `Done` obvious, with all later questions skippable.

## History

`History` should make historic reporting available inside the app rather than
only through scripts.

Useful views include:

- day list or calendar with status, outcome, robustness, and review state
- per-day detail with active window provenance, alternatives, HRV detail, data
  completeness, food/weight, and notes
- paired prediction/outcome summaries
- descriptive stability transitions
- irregular sleep buckets, no-sleep days, naps/rest, inferred windows, and
  zero-input days
- edit/create Journal entry for any selected date

History should stay humble. It can describe patterns and data coverage, but it
must not imply validated load-budget precision before the evidence exists.

## Settings

Settings should be grouped by purpose:

| Group | Contents |
| --- | --- |
| Daily flow | Marker mode, sync-window preset, default behavior. |
| Device | Selected Loop, scan/connect/disconnect, firmware, capabilities, controlled Flow maintenance. |
| Calibration and imports | H10/Sleep2 screenshot import, Health Connect permissions/export, FoodLogData and Grip Recorder folder/import settings. |
| Diagnostics | Sleep-report retry, JSON export, capability refresh, rare repair/debug actions. |

H10/Sleep2/Health Connect are calibration routes, not normal daily workflow.
Keep them available but out of the front-page flow.

Daily-flow settings should stay minimal during the data-collection phase. The
marker mode can imply the default evidence behavior:

- `No markers`: model/auto windows first, Loop report as fallback/context.
- `Bedtime`: model/auto windows first; bedtime markers provide sleep-latency
  and provenance evidence, with marker-derived fallback only when useful.
- `Bedtime + waking`: model/auto windows first; marker-derived windows become a
  stronger fallback and comparison source; Loop reports remain visible as
  context.

Only add an explicit preferred sleep-source setting if the inferred behavior
proves frustrating in real use.

## Implementation Guardrails

- Preserve local-first privacy assumptions.
- Do not make manual markers required for a usable read.
- Do not make sleep-window candidate review required unless the app genuinely
  needs user input.
- Do not preserve old `Now / Journal / History` or evidence-chip structure when
  it conflicts with the current product shape. For redesign tasks, "minimal"
  means the smallest coherent product experience, not the smallest possible
  diff.
- Keep inferred/model estimates labelled as estimates.
- Keep no-sleep/no-primary-window decisions explicit rather than fabricating a
  sleep window.
- Use the same active analysis-window provenance for status, Signals HRV,
  display, and history.
- Keep debug and calibration tools visually separate from daily controls.
- Prefer focused view-state objects over rebuilding interpretation directly in
  large composables.
- Add tests for marker modes, stale/missed markers, active provenance, time
  editing, no-sleep/no-primary-window decisions, and selected-date behavior.

## Follow-On Task Map

The first-pass redesign cards under `#256` implemented useful scaffolding, but
the final `#195` review/design contract clarified a stronger target shape. The
next implementation children should be treated as a coherence pass, not as
micro-patches to the existing surfaces:

- `Now` declutter and Journal capture: Daily Forecast as the status page,
  stepped Journal flow launched from Now, no HRV/evidence chips.
- App shell and `Signals`: replace the primary Journal tab with Signals once
  Journal capture/edit routes are available elsewhere.
- Signals sleep/rest and data detail: move repair, provenance, completeness, and
  current HRV detail out of Now.
- Autonomic scope infrastructure: shared PPI scope resolver, active
  sleep/rest default, rolling recent scopes, quality panel, and 24h shading.
- History detail: retrospective Signals-style autonomic detail and Journal
  edit/create route for selected dates.
- Docs/tests/QA: reconcile stale wording and verify the app reads as one product
  direction rather than a set of local edits.
