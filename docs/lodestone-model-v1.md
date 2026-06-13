# Lodestone Model v1 — Working Doc

Status: **draft for sign-off** (2026-06-13). This is an in-session working
document, not a kanban card — we implement it together and keep the intent live.
It replaces the legacy `scoreMorningRead` sleep-recovery score with the
evidence-backed shape from the June 2026 data-look. Uses the target names from
`docs/lodestone-naming-contract.md`.

## Why (the evidence in one paragraph)

On 41 days of SF, the wired forecast (sleep duration + mean overnight RMSSD) is
**non-predictive and slightly inverted** (rho ≈ −0.08 vs lived SF; "GOOD" days
lived worse than "OK" days) — it reproduces the Visible cognitive-dissonance
failure. What *does* hold: **SF is sticky** (persistence rho 0.53, p 0.002), and
**recent moderate/vigorous exertion (D−1/D−2) + 24h HRV CV predict
deterioration** (a leave-one-out backtest caught 4/5 historic downturns at no
accuracy cost, vs 0/5 for persistence alone). All directional, hypothesis-stage.

## The model

`deriveCurrentStateRead(...) -> CurrentStateRead`, in a new `CurrentStateModel.kt`
module (extracted out of `ProbeRepository`). Two honest elements + confidence:

### 1. Forecast (continuity) — the spine
- **Value = recent lived SF, carried forward (persistence).** Empirical basis is
  the lag-1 autocorrelation (yesterday→today, rho 0.53, p 0.002) — a ~1-day
  signal, NOT a long window. Uses the same `GOOD / OK / UNSTEADY / CRASH`
  vocabulary the journal outcome uses, so the forecast speaks the same language
  as the thing it predicts.
- **Persistence lookback:** carry the most recent outcome, *lightly smoothed over
  the last ~3 days* so a single odd or missing entry doesn't whipsaw the read
  (the user's journalling has ~20% gaps; the redesign should reduce these). The
  window length is a **fixed default + backtest knob**, NOT a fitted parameter —
  fitting on 41 skewed days would overfit and bias toward the modal UNSTEADY.
- If recent SF is missing/stale, confidence drops and the forecast is explicitly
  "based on older context" (`STALE_CONTEXT`), never fabricated; too old → `NONE`.
- It is **continuity of lived function, not a measured OF**. Copy must not imply
  objective capacity was observed.

### 2. Caution signal — beside the forecast, not a silent downgrade
- `cautionSignal: NONE | ELEVATED` (+ `reasons: List<String>`).
- **Triggers (either):** recent moderate/vigorous active-minutes in the user's
  own upper tier, OR 24h HRV CV in the user's own upper tier.
- **Exertion-caution lookback (distinct from the persistence lookback above):**
  D−1…D−3, dip peaks ~D−2 (matches the user's lived 2–3 day pattern). The
  journal-v2 contract's 1–5 day post-marker payback window is the outer bound;
  this is where the "~5 days" idea legitimately lives — it is the payback window,
  not the persistence spine.
- **It sits *beside* the forecast — it does not secretly lower the level.** The
  forecast stays "where you've been" (honest, measured-ish); the caution element
  carries "but load/instability is up — easing may be wise." When ELEVATED, the
  hero leads with the caution framing. (The backtest "downgrade" was a scoring
  convenience; in the UI we surface caution rather than fake a lower reading.)
- **`cautionSignal` carries a `kind`** so it can say two genuinely different
  things, even though v1 can only detect one:
  - `PUSH_RISK` (forward): "load/instability is up — easing may be wise; payback
    tends to land ~2 days out." This is what v1 detects (exertion + CV).
  - `BRITTLE_RECOVERY` (the asymmetric tail): "you're functional and recovering,
    but reserve is thin — don't bank on stamina." This is the true home of the
    reserved `StateStability` idea — distinct from push-risk. v1 leaves the slot
    and copy path in place; detection waits on a retrospective PEM-end signal.
  This is the user's "PEM is over but your strength may not last" case. The type
  must support both kinds from day one; the model populates `PUSH_RISK` for now.

### 3. Confidence — separate, honest, capped
- `confidenceLevel: LOW | MEDIUM | HIGH` from data coverage/quality/freshness/
  baseline (this is what the old fake "stability" actually measured).
- **Capped: never HIGH while the model is unvalidated**, and confidence is shown
  in the UI only when it is degraded or changes interpretation.

### Anti-Visible invariant (encoded + tested)
Good autonomic data must **never** produce a cheerful read that overrides a
recent run of poor lived function. Concretely: if recent SF is UNSTEADY/CRASH,
the forecast cannot present as GOOD on the strength of HRV/sleep alone. This is a
unit test, not just a guideline.

### Thresholds — personal and adaptive
Exertion and CV cut-points come from the **user's own trailing distribution**
(e.g. trailing-30-day upper tercile), not hardcoded population values — recomputed
as data accumulates. Fall back to provisional constants only until enough history
exists, and label them as provisional.

### Inputs, and what we drop
- **Use:** recent SF (journal outcome), recent M/V active-minutes
  (`daily_summary_raw` activityClassTimes), 24h HRV CV (`ppi247_epoch` good
  epochs, whole-day — **no sleep-window dependence**; this matters given the
  user's bradycardia and unreliable vendor sleep staging), data-coverage for
  confidence.
- **Drop from status scoring:** sleep duration, mean overnight RMSSD, linear HRV
  slope, the `baselineReady +0.25`-style nudges, Polar Nightly-Recharge maturity
  penalties. (Sleep/rest windows remain *provenance/context* in Signals, just not
  status inputs.)
- **Keep watching (not in v1 status):** 24h mean RMSSD → next-day (+0.30 lead),
  a proper U/"settling" overnight HRV *shape* feature (linear slope missed what
  the Garmin graphs showed by eye).

## PEM — inferred, not self-flagged
`pemPaybackToday` has never once been set (0/6) and self-flagging PEM in the
moment fails against poor interoception. v1: the **caution signal is the forward
PEM consideration** (exertion-driven). A *retrospective* PEM-episode detector
(post-exertion multi-day SF dip, surfaced in History) is a **later enhancement**,
noted not built. Do not gate anything on the prospective PEM checkbox.

## Persistence & schema
- New `CurrentStateRead` (in-memory) and `CurrentStateSnapshotEntity` /
  `current_state_snapshot` (persisted history), per the naming contract.
- New columns (stems): `forecastLevel`, `cautionLevel`, `cautionReasonsJson`,
  `exertionLoadRecent`, `hrvCv24h`, `confidenceLevel`, plus provenance/source.
- **Room migration (decided):** additive only — migration 27→28 adds
  `current_state_snapshot` and leaves `morning_prediction_snapshot` intact as
  read-only legacy so existing History rows survive. We do **not** copy old rows
  into the new table (the old `status` is the discredited sleep score; back-filling
  it as `forecastLevel` would launder the very thing we're replacing). History
  reads both tables until the redesign moves it onto `current_state_snapshot`.

## Validation loop (standing)
Port the leave-one-out backtest (`/tmp/hmexports/deepdive2.py`) into a repo
analysis script + a JVM-side check, reporting **accuracy vs baselines and
deterioration-catch rate** on whatever data exists. Re-runnable as data grows, so
the model has to keep earning its keep. This is the mechanism that lets v1 be
honest about being a hypothesis.

## Tests
- persistence carry-forward (incl. stale-SF → lower confidence path)
- caution triggers (exertion-only, CV-only, both, neither)
- **anti-Visible invariant** (recent UNSTEADY + good HRV ⇒ not GOOD/cheerful)
- confidence degradation by coverage
- adaptive-threshold computation (and provisional fallback)
- migration: existing history still loads

## Implementation order (in-session)
1. New types + `CurrentStateModel.kt` skeleton (naming contract names).
2. v1 rule (persistence + caution + confidence) with adaptive thresholds.
3. Room entity + migration; viewModel/flows wired to `CurrentStateRead`.
4. Delete `scoreMorningRead` and the fake `buildStateStability`.
5. Tests + ported backtest harness.
6. Verify on device against the freshly pulled DB.

## Leave room for: the loop-less (wearable-optional) shape
Do not make any choice that forecloses a no-Loop version that is still useful —
a low-friction journalling + passive-activity pacing tool that misses recovery
detail but tracks how the user is doing and *helps them build self-assessment*
(high value given the user's poor interoception). The architecture supports this
for free **if** we hold these invariants:
- **Lanes are optional and additive.** The forecast spine (persistence) computes
  from SF alone; HRV CV and autonomic confidence are enhancers, not requirements.
  Missing autonomic data lowers confidence and drops the CV caution — it never
  breaks the read. (The anti-Visible/confidence logic already does this.)
- **Source exertion from the phone too**, not only Polar (step counter / Health
  Connect), so the `PUSH_RISK` caution can partly survive without the Loop.
- Treat "promotes a more mindful outlook on assessing condition" as a product
  star, not something to design away.

## Out of scope for v1 (named so they're not lost)
Retrospective PEM-end detection (feeds `BRITTLE_RECOVERY`) · HRV shape feature ·
grip/cognitive probe lanes (too young) · food lane · phone-sourced exertion ·
the redesign shell (separate working doc) · the deferred naming/untangle sweep.
