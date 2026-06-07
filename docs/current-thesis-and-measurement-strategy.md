# Current Thesis And Measurement Strategy

Created from the 2026-06-07 modelling/design discussion. Treat this as the
current conceptual anchor for Lodestone. Older notes about morning readiness,
waiting for vendor sleep reports, Garmin sidecars, Polar cloud backfill, or
offline recording are historical unless explicitly reopened.

Use `docs/lexicon.md` as the canonical language guide when wording or term
boundaries are unclear.

## Product Aim

Lodestone is trying to help the user understand their lived state and make
safer pacing decisions when subjective judgement is unreliable.

The app should answer two linked questions:

- Why do I feel like this today?
- What does that imply about what I can safely try to do?

This is not just a sleep-status app and not just an HRV dashboard. Sleep, HRV,
food, grip, cognitive probes, and journal labels are evidence lanes for a
larger pacing question.

## State Concepts

Use these concepts consistently:

| Term | Meaning |
| --- | --- |
| Subjective function (SF) | The user's lived report and outcome: how the day felt, how usable they felt, and whether symptoms felt manageable. End-of-day journal outcome reports are SF by default. |
| Perceived function (PF) | The user's own estimate of capacity, if explicitly captured: what they think they can or could do. PF is user-perceived capacity, not Lodestone's derived forecast. It may be momentary or retrospective, but it is distinct from the model output. Current journal outcome reports do not automatically create a PF lane. |
| Objective function (OF) | What the user can actually produce or tolerate now. This is the action target, but it is hard to measure directly and should not be treated as one simple score. |
| Autonomic state | PPI/HRV/HR context: useful evidence about strain or possible recovery conditions, not a direct measure of usable function. |
| Recovery debt | The delayed and asymmetric cost of exertion. Exertion can be brief, while recovery can take days. |
| Planning state | Lodestone's derived user-facing current-state guidance assembled from SF history, any explicit PF field if one exists, autonomic data, sleep/rest evidence, grip, context, and history. It should support pacing, not claim certainty. |

SF stays primary because Lodestone is trying to explain and support the user's
lived state. OF is the practical target because the user wants to know whether
doing more is safe. PF is useful when captured, but the project exists partly
because perceived capacity alone has not been reliable enough to guide pacing.

PF and SF may turn out to be close enough that the model does not need both as
separate long-term concepts. Keep them separate for now where the journal or
UI explicitly distinguishes "how the day went" from "what capacity seemed
available." Do not call the app's derived forecast PF; call that `planning
state` or `current-state read`.

At the moment, Lodestone mostly uses SF history and functional context rather
than a separate PF data channel. Do not infer PF from ordinary end-of-day
outcome labels unless the UI explicitly asked the user to estimate capacity.

Treat functional planning as a bundle of related questions:

- **Current OF/capacity:** what can the user do now?
- **Stability or brittleness:** how robust is that apparent capacity?
- **PEM/payback risk:** what cost might follow if the user spends that capacity?

The current data may not separate those cleanly yet. Keep them conceptually
separate in implementation rather than collapsing capacity and future risk into
one "OF score". The docs should not imply that grip strength, HRV, or any
single lane fully answers the planning question.

Some code, scripts, and database fields still use `readiness` because they were
created before this reframing. Treat that as legacy naming for current-state or
planning-state work until those identifiers are deliberately renamed.

## Current Recovery Thesis

The working model is asymmetric and delayed:

- Dropping into a lower-function state can happen quickly after exertion,
  poor sleep, infection, orthostatic load, emotional/cognitive load, or other
  stressors.
- Recovery is slower. Good autonomic data may mean the system is in a better
  condition to recover, but it does not mean usable function has already
  returned.
- Several good autonomic days may be needed before SF/PF/OF improve.
- Subjective function may have inertia: the user can remain in a low-function
  spell even after the autonomic lane looks less strained.

Therefore HRV/PPI is best treated as recovery context or possible evidence that
conditions for recovery are improving, not as a direct OF measure. A "good"
autonomic read should not erase recent PEM, poor function, or objective
weakness.

The recovery focus must not hide exertion and drain. Lodestone needs to learn
what lowers function as well as what recovery may look like. Because detailed
exertion journaling is unlikely to be sustainable, prefer passive signals,
simple day-shape chips, and occasional objective probes over high-friction logs.

## Sleep And Rest

Sleep and rest remain important recovery evidence, but they are no longer the
central frame.

The app should not force irregular sleep, interrupted sleep, insomnia, naps, or
rest into one canonical "main sleep" story when that framing is not useful.
Sleep/rest windows are evidence and provenance for interpreting autonomic data.
They are not the throne.

Existing schema values such as `main_sleep` and `nap` are internal shortcuts
from the older frame. They may remain while they are useful, but user-facing
language and new modelling work should prefer "sleep/rest windows" unless a
task is explicitly about those legacy categories. If the project becomes
confident it will not return to a sleep-centred model, schedule a deliberate
rename/removal rather than letting the names bias future design.

Vendor sleep reports, H10/Sleep2 captures, and Health Connect exports are
calibration or fallback evidence. The current daily app should present a usable
current-state/planning read when local data is good enough, without implying
that the user is waiting for a final Loop sleep report.

## Measurement Burden

Every data channel has a burden and may change the system being measured.

| Channel | Main value | Burden / risk |
| --- | --- | --- |
| PPI/HRV/HR | Mostly passive autonomic context. | Device/sync reliability, interpretation lag, not direct OF. |
| Journal/SF labels | Primary lived outcome. | Cognitive cost can be highest exactly when labels are most valuable. |
| Day-shape chips | Low-friction context receipts. | Still require attention and consistency. |
| Grip check | Direct physical OF probe for one narrow domain. | Physically invasive; can cause local strain or affect next-day readings. |
| Cognitive check/PVT | Direct attention/processing probe. | Cognitive exertion may affect state; device latency/noise. |
| Tetris/block task | Naturalistic visuomotor/cognitive probe. | Practice effects, motivation, strategy, and addictive/retry pressure. |

The best measure is not simply the most objective one. It is the most useful
objective signal with an acceptable burden on bad days.

## Grip Strength

Grip Recorder owns the timed measurement UX and exports disposable CSVs.
Lodestone imports sessions into its main database and owns long-term
interpretation.

Grip is currently a functional-lane OF probe, not a universal capacity test. It
may reflect:

- local hand/forearm strength
- repeatability and fatigability
- systemic recovery state
- recent physical measurement cost

`check_2` means the low-friction daily grip protocol: two recorded repetitions
from the Grip Recorder flow, currently treated as the sustainable default unless
the user deliberately runs a longer protocol. It is not a two-second hold, a
2 kg threshold, or a diagnostic test.

Important interpretation rules:

- A low grip day after a longer grip protocol may reflect measurement-induced
  local or systemic recovery debt, not spontaneous capacity loss.
- A two-rep daily check is the sustainable default.
- Longer evidence protocols should be rare and explicitly marked as exertion
  events.
- The model should be allowed to learn that grip testing itself can affect later
  SF/PF/OF.

Grip may become one anchor for OF, but it cannot represent cognitive,
orthostatic, sensory, pain, or whole-body endurance limits by itself.

## Cognitive Probes

Exploratory standalone probes are worth trying if they stay bounded and cheap.
They should stay outside Lodestone at first: separate recorder app, disposable
CSV export, and no main-app import until sustainability and explanatory value
are plausible.

Two weeks of data is enough to judge burden, adherence, and any obvious signal.
It is not enough to prove subtle effects. If a probe only looks useful through a
subtle effect that would require much longer collection, be sceptical about
integrating it because it may not justify the cognitive cost.

Preferred first cognitive probe:

- A short psychomotor-vigilance style tap task (`PVT-lite`).
- Default duration around 60 seconds.
- Metrics: median reaction time, slowest tail, lapses, false starts, variability.
- No score, leaderboard, retry encouragement, or achievement loop.

Possible secondary probe:

- A fixed-duration Tetris-like block task.
- Treat as exploratory only because score is confounded by practice, strategy,
  motivation, risk-taking, and game stickiness.
- Use fixed duration, fixed speed where possible, and no "play again" prompt.

Both cognitive probes are interventions as well as measurements. Cognitive
exertion can be a PEM trigger, so these tasks must be skippable and burden-rated.

Timing and state labels matter. A probe done soon after waking may measure
sleep inertia or alerting rather than ME/CFS fatigue or brain fog. A probe done
after activity may measure exertional cost. This does not need to become a
manual logging burden: the probe timestamp, CSV file creation time, and
Lodestone sleep/rest-window data should usually be enough to classify time since
likely waking.

That derived timing needs a confidence path. If the active sleep/rest window is
low confidence, missing, interrupted, or ambiguous, mark time-since-waking as
uncertain rather than fabricating a precise category.

Sleep inertia can vary from almost none to several hours depending on condition
and arousal state. Use `arousal state` rather than asking the user to separate
anxiety from excitement when that distinction is not reliably available.

Record enough context to distinguish at least:

- **sleepy:** not fully awake, sleep inertia, or circadian timing
- **fatigued:** low energy or reduced stamina
- **fogged:** attention, processing, memory, or executive-function impairment
- **high arousal:** activated, keyed up, anxious, excited, or otherwise alert in
  a way that may temporarily mask sleepiness or fatigue

Avoid treating `tired` as a precise label. It does too much work and can hide
whether the useful contrast is sleepiness, fatigue, fog, mood, pain, or
orthostatic load.

Puzzle-like self-checks such as solitaire or hard minesweeper may be useful as
personal wake-up calibration because they mirror the user's historic strategy:
prove the brain has come online enough to win once. If explored, cap attempts
and log timing, attempts, and time-to-first-win. Otherwise the task can become a
variable-duration exertion/retry loop rather than a bounded measurement.

## Modelling Direction

Lodestone should compare and preserve disagreements between lanes rather than
trying to collapse them too early.

Useful disagreement patterns:

- Low SF/PF plus low grip: likely real functional hit.
- Good autonomic state plus low grip: possible recovery debt despite better
  recovery conditions.
- Low SF/PF plus normal grip: limiting domain may be cognitive, orthostatic,
  pain, sensory, sleep, or mood rather than peripheral strength.
- Good SF/PF plus strained autonomic data: possible caution flag or early
  stress signal, not proof of impending crash.

The key modelling question is:

> When subjective function and objective signals disagree, which signal better
> predicts tomorrow's function, PEM, and safe usable capacity?

This makes cognitive dissonance a modelling feature instead of a UX failure.

## Daily-Use Principle

Keep the core flow resilient to fatigue, brain fog, and ADHD:

- One-tap journal remains valid.
- Optional measurements must be skippable without guilt.
- Do not add enough tasks that the user drops the whole project.
- Prefer a small number of sustainable channels over an impressive but fragile
  measurement stack.

If a channel becomes too costly, too noisy, or too likely to alter the state
being measured, pause it or replace it with a lower-burden probe.
