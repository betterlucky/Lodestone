# Journal V2 And Current-State Model Contract

This note captures the design shift agreed after the first Lodestone redesign
pass. It should guide follow-on work for Kanban cards `#181`, `#182`, `#184`,
and the later copy pass `#188`.

Read `docs/lexicon.md` and
`docs/current-thesis-and-measurement-strategy.md` first for the current
SF/PF/OF framing, delayed-recovery thesis, and measurement-burden rules. This
document remains active, but older `readiness` wording should be interpreted as
the current planning/current-state signal rather than a sleep-gated morning
verdict.

## Product Constraint

The most valuable labels are needed when energy, focus, and executive function
are likely to be lowest. Journal capture must therefore be designed for low
motivation, brain fog, fatigue, and post-hyperfocus drop-off.

The app should not depend on thoughtful daily journalling. A one-tap entry must
remain valid and useful.

## Journal V2

The core Journal entry remains the evening outcome:

- `GOOD`
- `OK`
- `UNSTEADY`
- `CRASH`

`CRASH` should stay an absolute severity label for close-to-worst days, not a
catch-all for any meaningful PEM. This avoids forcing the user to overuse a
label that feels too severe.

The minimum valid entry is:

1. Pick an outcome.
2. Save.

Everything else is optional.

## Day Shape Chips

Add a small, fixed set of behavioural anchors under user-facing wording such as
`Today included` or `Day shape`. Do not call these `tags` in the UI.

Initial chips:

- `Mostly horizontal`
- `Left the house`
- `Work / major task`
- `PEM / payback today`

These are deliberately broad. They should feel like observable receipts for the
day, not a taxonomy to maintain.

If `Work / major task` is tapped, an optional subtype can be offered:

- `Work from home`
- `Site visit`
- `Admin / assessment`
- `Other major task`

The subtype must not be required. Site visits are worth distinguishing because
they appear to produce much stronger payback than coding or lighter cognitive
work.

## Missing Means Unknown

Historic rows have no day-shape chips. One-tap rows may also skip the chip
section. Reports and models must treat missing chips as unknown, not as false.

Recommended persistence:

- Store a `dayShapeCaptured` marker.
- Store chip booleans as nullable.
- If `dayShapeCaptured` is not true, chip values are unknown.
- Once the user interacts with the chips, untapped chips can be stored as false.

## Payback Episodes

`PEM / payback today` is a daily marker. It should be tappable in the normal
Journal flow.

`Peak of payback` is a retrospective marker. It means:

> Looking back over this payback spell, this day was probably the worst day.

It does not mean the day was an absolute `CRASH`.

The prompt should be episode-aware:

- Detect one or more consecutive days marked `PEM / payback today`.
- On the first later Journal/Catch-up interaction where PEM is not marked,
  offer one lightweight prompt to choose the peak day.
- Options should be the recent PEM dates plus `Not sure`.
- The prompt must not block saving.
- The prompt should not repeat after it has been answered or dismissed for that
  episode.

If a payback spell has only one marked PEM day, it can be auto-treated as the
peak with low confidence or prompted only if that stays low friction.

## Current-State Model Lanes

The model should split concepts that were previously collapsed into one
legacy readiness/status output.

Current conceptual terms:

| Term | Meaning |
| --- | --- |
| Subjective function (SF) | Lived day state and outcome. Primary because Lodestone is trying to explain how the user feels. End-of-day journal outcome reports are SF by default. |
| Perceived function (PF) | User-estimated capacity, if explicitly captured. PF is what the user thinks they can or could do, not Lodestone's derived forecast. Current journal outcome reports do not automatically create a PF lane. |
| Objective function (OF) | What the user can actually produce or tolerate now. Treat OF, stability/brittleness, and PEM/payback risk as related but separate planning concepts, not one simple score. |
| Exertional load (EL) | What load the user actually incurred. Sensor activity, site visits, leaving the house, work, and grip-test burden are EL/context, not safe capacity. |

| Lane | Meaning |
| --- | --- |
| Autonomic lane | PPI, HRV, HR, sleep/window evidence, Nightly Recharge support. |
| Functional lane | Recent outcomes, day-shape chips, PEM markers, reported activity/load markers, grip sessions, and future objective-function probes. |
| Planning state | Conservative user-facing guidance that combines the lanes. |

The app's derived forecast is `planning state` or `current-state read`, not PF.
Use PF only for user-perceived capacity fields or labels.

For the current Journal V2 shape, assume the functional lane is mostly SF
history, PEM/payback markers, EL/context, and objective probes. PF is a
reserved concept unless the UI explicitly captures a capacity estimate. A flag
can be journal-sourced without being SF-semantic; for example `left_house` is a
reported activity/load marker.

The planning state must not be `GOOD` solely because HRV looks good when recent
function says the user is in a poor-function spell.

The current working model is asymmetric:

- Downgrades can happen quickly when function, PEM, or clear autonomic strain
  looks poor.
- Upgrades should be slower. A recent rough functional state should persist
  until there is at least one later `OK`/`GOOD` functional outcome, and recovery
  should normally climb to `OK` before `GOOD`.
- Good HRV/PPI should be treated as possible recovery context, not immediate
  permission to spend energy freely.
- Exertion and other drains must remain part of the model, but avoid turning
  them into a high-friction journaling requirement.
- Sleep/rest windows are evidence and provenance for autonomic reads, not the
  central model anchor.

Useful mixed-state copy pattern:

> Autonomic signal looks steady, but recent outcomes suggest a lower-function
> spell.

This is not a contradiction. It is the point of the lane split.

Autonomic context should be descriptive until there is more data. Current
candidate labels:

| Label | Meaning |
| --- | --- |
| Autonomic strain | Low-tail HRV or average RMSSD is low enough to treat the signal cautiously. |
| Autonomic watch | Low-tail HRV is mildly subdued. |
| Recovery momentum | The HRV trajectory rises or dips then recovers, but this is not an upgrade by itself. |
| Strained, recovering | Low-tail HRV is still strained, while the trajectory rises later in the window. |
| Autonomic steady | The current HRV distribution and curve do not add an obvious strain flag. |
| Autonomic drift down | The trajectory falls later in the window. |

The useful hypothesis to track is that low-tail HRV (`p25`/`p10`) and resting HR
may flag strain, while upper-tail HRV (`p75`/`p90`) and rising curves may show
recovery potential before usable function returns. Do not promote upper-tail HRV
or a rising curve into a direct `GOOD` planning state without matching
functional evidence.

## Historic Data

Existing outcome rows remain valuable as functional-state labels. New chips and
payback markers start later and must not make older rows look like clean
negative examples.

Reports should be explicit about era and completeness:

- outcome-only historic data
- Journal V2 chip data
- objective measurements such as grip sessions and future cognitive probes

Grip strength should stay in the functional lane. The low-friction manual daily
field remains a fallback for a single reading; timed protocol sessions should be
imported from Grip Recorder session CSVs and stored separately as sessions plus
reps. Treat the source CSVs as disposable once Lodestone has imported them.

`check_2` means the two-repetition Grip Recorder protocol. It is not a two-second
hold, a 2 kg threshold, or a diagnostic category. It should remain available,
but the lower-burden fallback is a single manual pull when recorder setup is too
hard in the morning.

Grip is both a measurement and a small exertion event. Daily grip readings
should be interpreted as a narrow physical OF probe, not a complete measure of
whole-body, cognitive, orthostatic, or sensory capacity. Longer grip protocols
should be marked as evidence/exertion events because they can affect later
readings.

Cognitive probes such as PVT-lite or bounded Tetris-like tasks are exploratory.
If tried, they should live in separate recorder apps first, export disposable
CSVs, and be treated as interventions as well as measurements. Two weeks can
test burden and obvious signal, but subtle effects need more evidence and may
not justify integration.

If a cognitive probe is trialled, capture timing context. Early-morning tests
may measure sleep inertia or alerting; post-activity tests may measure
exertional cost. The probe timestamp, CSV file creation time, and Lodestone's
own sleep/rest-window data should usually be enough to estimate time since
likely waking without asking for extra manual labels. If the sleep/rest window
is low confidence, missing, interrupted, or ambiguous, mark the derived timing
as uncertain rather than fabricating precision. Avoid using `tired` as the main
label when more specific states such as sleepy, fatigued, fogged, or high
arousal are available.

## Analysis Direction

Delayed PEM should be analysed over lag windows, not only next-day transitions.
Initial reports should use 1-5 day windows after major-task/site-visit markers
and show:

- trigger/context day
- affected PEM days
- peak payback day if known
- recovery tail
- missing labels

These are descriptive reports, not causal claims.

Autonomic-lane reports should similarly remain descriptive:

- same-day rough outcome rate for strain and recovery-momentum flags
- next-day movement after those flags
- lag windows once there are enough labelled days
- separate treatment of sleep/rest-window features and full rolling-day features

With the current small single-person dataset, report deltas are hypothesis
generation only. The robust conclusion so far is that functional inertia matters
much more than raw autonomic optimism.
