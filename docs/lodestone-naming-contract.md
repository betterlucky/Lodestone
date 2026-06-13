# Lodestone Naming Contract

Status: **draft for sign-off** (2026-06-13). This is the linchpin document for the
redesign + model-v1 work. Read it before touching code.

## Why this exists

Lodestone's code still speaks the abandoned *morning-readiness / sleep-score*
frame: `MorningReadSnapshot`, `scoreMorningRead`, `readiness`, a `DataScreen`
that is really the `Now` tab, and a `stability` that actually means PPI data
coverage. Names are load-bearing: every agent (human or AI) that reads these
re-absorbs the old frame, which is a measurable source of the model/UI drift
this project keeps fighting. `docs/current-thesis-and-measurement-strategy.md`
and `docs/lexicon.md` define the *product* language; this document maps that
language onto *code identifiers* so new code is born correct.

This contract does **not** require a big-bang rename. It stops new drift
immediately (any code written from now uses target names) and tags each legacy
identifier with *when* it gets renamed.

## Principles

1. **User-facing vs internal are different vocabularies.**
   - User-facing hero label: **"Daily Forecast"** (or "Forecast"). Approachable,
     implies uncertainty without apology.
   - Internal type for the model output: **`CurrentStateRead`** (per lexicon's
     "Current-State Read"). Never expose `PlanningState` / `CurrentStateRead` as
     polished hero copy; never call the hero "readiness".
2. **Banned stems** in all *new* code identifiers and *new* user-facing copy:
   `readiness`, `morning*` (as a status frame), and `stability` (as a synonym for
   data confidence — see below). Existing instances are migrated per the table.
   External vendor JSON keys (e.g. `readinessForSpeedAndStrengthTraining`) are
   exempt — they are Polar's, not ours.
3. **The three "stabilities" are split, and the word is reserved.** Today
   `stability` is overloaded across three unrelated concepts. New code uses:
   - **`signalConfidence`** — is the read well supported by data coverage,
     quality, freshness, baseline? (This is what the old `NowStateStability` /
     `buildStateStability` actually computed.) User-facing word: **Confidence**,
     shown only when degraded.
   - **`cautionSignal`** — is a downturn more likely? Driven by recent
     moderate/vigorous exertion (D−1/D−2) + 24h HRV CV. Carries both the
     present-tense "unsteady/brittle now" framing and the future-tense
     "PEM worth considering" framing (these are one engine on current data; see
     `lodestone-datalook-jun2026` memory).
   - **`StateStability`** — *reserved*. Do not use until/unless the data ever
     yields an independent brittleness measure distinct from `cautionSignal`.
   - History's day-to-day status change keeps a transition name:
     `stabilityTransitionLabel` → **`stateTransitionLabel`**.
4. **The forecast content is continuity, not OF.** `CurrentStateRead` represents
   *persistence of recent lived function + a caution layer*, not a measured
   objective-function score. Do not name fields as if OF were observed.

## Mapping table

Scope tags: **[model-v1]** rename as part of the model rewrite (we touch these
anyway); **[redesign]** rename as part of the UI shell rebuild; **[sweep]**
deferred peripheral pass once the shape is settled. "→ replaced" means the
identifier is deleted/rewritten, so the new code is simply born with the new
name rather than mechanically renamed.

### Model output & engine
| Legacy | Target | Scope | Notes |
| --- | --- | --- | --- |
| `MorningReadSnapshot` (live) | `CurrentStateRead` | model-v1 | the in-memory read |
| `MorningPredictionSnapshotEntity` / table `morning_prediction_snapshot` | `CurrentStateSnapshotEntity` / `current_state_snapshot` | model-v1 | needs Room migration; persisted history |
| `deriveMorningRead` | `deriveCurrentStateRead` | model-v1 | |
| `scoreMorningRead` / `MorningScoreResult` | → replaced by `CurrentStateModel` (persistence + caution) | model-v1 | sleep-score engine deleted; new module `CurrentStateModel.kt` |
| `provisional*MorningRead*`, `noMainSleepMorningRead` | `provisional*CurrentStateRead*`, `noMainSleepCurrentStateRead` | model-v1 | |
| `MorningReadSource` | `AnalysisWindowSource` | model-v1 | aligns with redesign doc "active analysis-window provenance"; keep enum values |
| snapshot columns `nightlyRmssd`, `sleepDurationMinutes`, `rawPpi*`, `overnightAutonomicSource`, `isInterim`, `sleepDataReady` | new schema (implemented): stems `forecastLevel`, `forecastBasis`, `cautionLevel`, `cautionKind`, `cautionReasonsJson`, `confidenceLevel`, `recentOutcomeLevel`, `exertionLoadRecent`, `hrvCv24h` (matches `docs/lodestone-model-v1.md` and `CurrentStateSnapshotEntity`) | model-v1 | old columns kept read-only for history during migration |
| `MorningReadScheduler.kt` | `CurrentStateScheduler.kt` | sweep | peripheral |

### Now UI (the `Now` tab)
| Legacy | Target | Scope | Notes |
| --- | --- | --- | --- |
| `DataScreen` / `TodayScreen.kt` | `NowScreen` / `NowScreen.kt` | redesign | it *is* the Now tab |
| `TodayComponents.kt` | `NowComponents.kt` | redesign | |
| `TodayHeroCard` | `NowHeroCard` | redesign | |
| `TodayReadinessStatus` / `todayReadinessStatus` / `TodayReadinessStage` | → replaced (fold into `NowCurrentState`) | redesign | |
| `TodayDataQualityState` / `TodayDataQualitySummary` | `SignalConfidenceState` / `SignalConfidenceSummary` | redesign | the confidence concept |
| `NowStateStability` / `buildStateStability` / `stateStability` | → merged into `signalConfidence` (data) and `cautionSignal` (brittleness) | model-v1/redesign | the central disambiguation |
| `stabilityLabel` (TodayComponents) | → deleted (dead code) | redesign | unused |
| `MorningReadCard` (LodestoneComponents) | → deleted (dead code) | redesign | unused |
| `heroStabilityFact` | → repurposed to `heroCautionFact` (only when elevated) | redesign | |
| `MorningSignalSection`, evidence `FilterChip`s | → replaced by single-entry Signals sections | redesign | removes chip-rack drift |

### Sleep/window selection ("readiness" family)
| Legacy | Target | Scope | Notes |
| --- | --- | --- | --- |
| `SleepEpisodeReadinessRules` | `SleepEpisodeSelectionRules` | sweep | rename opportunistically if model-v1 touches it |
| `isPrimaryForReadiness` | `isSelectedPrimaryWindow` | sweep | |
| `selectedPrimaryReadinessEpisode` | `selectedPrimaryWindowEpisode` | sweep | |
| `hasPrimaryReadinessWindow` | `hasPrimaryWindow` | sweep | |
| `toPrimaryReadinessWindow` | `toPrimaryWindow` | sweep | |
| `latestReadinessSync` | `latestCheckInSync` | sweep | |

### Sync / scheduling notes
| Legacy | Target | Scope | Notes |
| --- | --- | --- | --- |
| `SyncRunProfile.MORNING_CORE` etc., "morning core sync" strings | `CHECK_IN_CORE` / "check-in core sync" | sweep | `Check in` is the current daily verb (lexicon) |
| `MORNING_READ` / `MORNING_TARGET` / `MORNING_RETRY` constants | `CURRENT_STATE_*` equivalents | sweep | |

### Analysis scripts (repo-level, non-app)
| Legacy | Target | Scope | Notes |
| --- | --- | --- | --- |
| `readiness_outcome_report.py`, `old_readiness` fields, `morning_model_report.py` | current-state / forecast equivalents | sweep | not blocking; rename with the deferred pass |

## Rules for any agent working in this repo

- Before writing or editing code in the forecast / Now / Signals / sleep-window
  areas, read this contract and `docs/lexicon.md`.
- New identifiers use target names. Do **not** reintroduce banned stems.
- If you must touch a legacy identifier that is in scope for a later pass and
  renaming it now would balloon the diff, leave it but do not propagate it into
  new code; add a `// TODO(naming): -> <target>` only if it aids the sweep.
- `Now*` as a prefix for Now-tab view-state is fine and encouraged (it matches
  the tab, not the old frame).
