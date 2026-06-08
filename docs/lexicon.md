# Lodestone Lexicon

This is the canonical language guide for Lodestone's modelling and product
terms. Use it to avoid reintroducing stale assumptions from older sleep-centred
or readiness-centred work.

Statuses:

- **Active:** use this term in current product and modelling discussion.
- **Reserved:** concept is useful, but not currently a normal data lane.
- **Legacy:** identifier or phrase may still exist in code/schema/docs, but do
  not add new user-facing language with this framing.
- **Experimental:** allowed for trials, but not yet part of the core model.

## Function And Planning

**Subjective Function (SF)** - Active:
The user's lived report and outcome: how the day felt, how usable they felt,
and whether symptoms felt manageable. End-of-day journal outcome reports are SF
by default. Journal-sourced activity flags are not automatically SF just because
they are captured in the same flow.
_Avoid_: treating ordinary journal outcome labels as PF; treating `left_house`
or similar activity/load markers as symptoms or lived-outcome labels.

**Perceived Function (PF)** - Reserved:
The user's own estimate of capacity, if explicitly captured: what they think
they can or could do. PF is user-perceived capacity, not Lodestone's derived
forecast, and current journal outcome reports do not automatically create a PF
lane.
_Avoid_: planning state, current-state read, inferred capacity.

**Objective Function (OF)** - Active:
What the user can actually produce or tolerate now. OF is a current-capacity
concept, not a blended score for future cost.
_Avoid_: PEM risk, stability score, grip score.

**Stability / Brittleness** - Active:
How robust apparent capacity seems. A user may have some current OF while still
being brittle enough that small exertion has disproportionate cost.
_Avoid_: treating stability as the same thing as capacity.

**PEM / Payback Risk** - Active:
The possible future cost of spending capacity. It belongs beside OF in planning,
but it is not OF itself.
_Avoid_: objective function, current capacity.

**Exertional Load (EL)** - Active:
What load the user actually incurred: steps, MET minutes, active time, site
visits, work, leaving the house, grip-test burden, and other exertional or
participation demands. EL is most useful as trigger/context evidence for
delayed PEM or recovery debt. It can help infer OF only when paired with later
outcomes.
_Avoid_: OF proxy, Actual Function, safe capacity.

**Planning State** - Active:
Lodestone's derived user-facing guidance assembled from SF history, any explicit
PF field if one exists, autonomic data, sleep/rest evidence, EL/context, grip,
and history. It should support pacing, not claim certainty.
_Avoid_: PF, diagnosis, readiness.

**Current-State Read** - Active:
Synonym for the model output shown to the user at check-in time. Prefer this
when discussing the app's read "as things stand".
_Avoid_: morning readiness, PF.

## Evidence Lanes

**Functional Lane** - Active:
Evidence about function, load, and outcomes: SF history, PEM/payback markers,
day shape, major tasks, reported activity markers, grip sessions, and future
objective probes.
_Avoid_: subjective-only lane.

**Reported Activity Marker** - Active:
A low-friction user-reported activity/load flag captured through journal or
check-in UI. Its source is the journal, but its construct is usually EL/context,
not SF. Examples: `left_house`, `site_visit`, `worked_from_home`, errands, or
mostly-horizontal/rest markers.
_Avoid_: assuming journal source means SF semantics.

**Autonomic Lane** - Active:
PPI/HRV/HR and related context for strain, possible recovery conditions, and
recovery momentum. It is useful evidence, not direct usable capacity.
_Avoid_: recovery permission, objective function.

**Autonomic State** - Active:
The current or recent pattern in autonomic evidence. Use it as context for
strain or possible recovery conditions, not as a verdict about what the user can
do.
_Avoid_: readiness, capacity.

**Recovery Debt** - Active:
The delayed and asymmetric cost of exertion. Exertion can be brief while
recovery can take days.
_Avoid_: same-day fatigue only.

## Sleep And Rest

**Sleep / Rest Window** - Active:
A period that Lodestone, the user, or a supporting source treats as sleep,
rest, or sleep-adjacent evidence. Windows are provenance and context, not the
central product anchor.
_Avoid_: forcing every window into a canonical main sleep story.

**Primary Window** - Active:
The selected window used by a particular analysis or display. It is not a claim
that the user had one true main sleep.
_Avoid_: readiness window.

**No-Sleep / No-Primary-Window Decision** - Active:
A user or model decision that no useful primary sleep/rest window should be
fabricated for that source date.
_Avoid_: no-main-sleep as user-facing language.

**Main Sleep** - Legacy:
Older schema/category language for a primary sleep episode. It may remain in
code or stored data, but new UI and model discussion should prefer sleep/rest
window language unless the legacy category is directly relevant.
_Avoid_: using as the product worldview.

**Nap** - Legacy:
Older schema/category language for a shorter sleep episode. It may be useful as
a rough stored category, but interrupted sleep and irregular sleep should not be
forced into "nap" framing.
_Avoid_: treating as a precise user-facing distinction.

## Legacy Product Language

**Readiness** - Legacy:
Older wording for the morning/sleep-anchored status. Existing code, scripts, and
database fields may still use this name; interpret it as current-state or
planning-state work unless the task is explicitly about renaming identifiers.
_Avoid_: adding new user-facing readiness copy.

**Morning Read** - Legacy:
Older internal name for the daily status derived around morning wake/sleep
assumptions. Current product framing is not morning-bound.
_Avoid_: using as new product language.

## Measurements And Probes

**Grip Check** - Active:
A narrow physical OF probe using the separate Grip Recorder flow. It can be both
a measurement and a small exertion event.
_Avoid_: whole-body capacity test, diagnostic strength test.

**check_2** - Active:
The low-friction daily grip protocol: two recorded repetitions from Grip
Recorder.
_Avoid_: two-second hold, 2 kg threshold.

**FoodLog Import** - Active:
Imported food and weight evidence from the separate FoodLog workflow. It remains
an integrated lane even if logging pauses.
_Avoid_: requiring food logging for daily app value.

**Cognitive Probe** - Experimental:
A bounded external task such as PVT-lite, Tetris-like play, solitaire, or
minesweeper used to explore cognitive OF. Keep outside Lodestone until burden
and obvious value are plausible.
_Avoid_: achievement loop, retry-until-good-score task.

**Sleepy** - Active:
Not fully awake, sleep inertia, or circadian timing effects.
_Avoid_: tired.

**Fatigued** - Active:
Low energy or reduced stamina.
_Avoid_: sleepy, fogged, tired.

**Fogged** - Active:
Attention, processing, memory, or executive-function impairment.
_Avoid_: tired.

**High Arousal** - Active:
Activated, keyed up, anxious, excited, or otherwise alert in a way that may
temporarily mask sleepiness or fatigue.
_Avoid_: forcing anxiety vs excitement when the user cannot reliably separate
them.

## Device And Workflow

**Check In** - Active:
The normal user-triggered sync and current-state assessment flow.
_Avoid_: morning-only sync.

**Catch Up** - Active:
The repair flow for stale or missing dates/data. It should be driven by data
freshness, not by app-open recency alone.
_Avoid_: suppressing because Last used is recent.

**Flow Handoff** - Active:
The practical device-sharing problem where Polar Flow and Lodestone may compete
for the same BLE relationship. The app should support graceful messaging and
retry without treating Flow sleep reports as the daily gate.
_Avoid_: assuming seamless coexistence.
