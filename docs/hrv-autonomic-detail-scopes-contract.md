# HRV / Autonomic Detail Scopes Contract

Kanban `#195`. Design-only contract — **implementation is explicitly deferred**
until this document is agreed.

Read first:

- `docs/current-thesis-and-measurement-strategy.md` — autonomic lane is recovery
  context, not usable capacity; sleep/rest windows are evidence and provenance.
- `docs/journal-v2-current-state-contract.md` — autonomic labels, strain/momentum
  rules, and planning-state asymmetry.
- `docs/lodestone-redesign-architecture.md` — active analysis-window provenance
  must be shared across status, HRV detail, evidence copy, and history.
- `docs/lexicon.md` — term boundaries; `Main sleep` / `Nap` are **legacy schema
  only** — do not use in new UI or contract language.

## Problem

The app currently exposes one HRV view: a **sleep/rest-window trajectory**
inside the active analysis window. The trajectory dialog title is still
`Overnight HRV trajectory`, and `MorningReadSnapshot.hrvTrajectory` is built
only from PPI_247 epochs fully contained in that window
(`ProbeRepository.summarizePpi247ForSleepWindow`).

That is the right default for recovery interpretation, but users may also want
to inspect shorter recent windows (for example after exertion, orthostatic load,
or a later rest period). Daytime and mixed-condition PPI is **not** equivalent
to sleep/rest recovery HRV. A scope selector must make that distinction legible
and must not let noisy daytime trends upgrade planning state.

## Goals

- Default to the **active sleep/rest window** (same provenance as the current
  read).
- Allow optional **rolling recent** scopes: past 1h / 2h / 4h / 8h / 24h.
- Define which PPI epochs are eligible per scope and how quality limits are
  shown.
- Decide which scopes may influence **planning state** vs **descriptive-only**
  detail.
- Recommend UI labels and selector ordering.
- List follow-up implementation tasks.

## Non-goals (this contract)

- Changing autonomic strain/momentum thresholds or morning score weights.
- Promoting daytime HRV into baseline or recovery-debt models.
- Adding new sync domains or extending PPI retention beyond current settings.
- Replacing Nightly Recharge; raw PPI remains the primary in-app trajectory
  source.
- Reintroducing legacy product framing (`main sleep`, `nap`, `overnight`) in
  user-facing copy.

## Current implementation anchor

| Piece | Current behaviour |
| --- | --- |
| PPI epochs | 5-minute windows from `Ppi247EpochBuilder` (`ppi247_epoch` table). |
| Usable sample gate | PPI 300–2000 ms, skin contact, interval online, error ≤ 50 ms. |
| Epoch quality | `good`, `usable`, `review`, `poor_sparse`, `poor_contact_or_error`. |
| Trajectory inclusion | `epochQuality == "good"` and `rmssdMs != null` only. |
| Window filter | Epoch fully inside active sleep/rest `[start, end]`. |
| Minimum for labels | ≥ 12 good epochs (`MIN_MORNING_PPI_GOOD_EPOCHS`). |
| UI | `HrvTrajectoryDialog` — single window only; no selector. |
| Planning use | `NowScreenState.buildAutonomicContext` uses the same window trajectory for strain/momentum labels. |
| Reports | `scripts/model_engine.py` splits overnight PPI and post-wake daytime PPI (movement/contact ratios). |

The contract aligns the app with the sleep/rest vs daytime split instead of
treating all PPI as one recovery signal.

## Design decision: no separate “last sleep” scope

Kanban `#195` originally listed **last sleep** alongside rolling scopes. After
review, this contract **drops it as a first-class selector option**.

### Why it was proposed

The idea was: the **active sleep/rest window** (what planning uses) might not be
the **most recently ended** recorded sleep/rest episode — for example after a
later rest period, or when the model kept an earlier window as primary.

### Why it does not earn a daily selector slot

| Question | Answer |
| --- | --- |
| What job is the user hiring this for? | Recovery-context HRV for pacing (`active_sleep_rest`) or recent pattern (`rolling_*`). |
| When would “last recorded” differ from active? | Uncommon; usually a provenance or repair situation, not a third daily mode. |
| Does rolling cover the “what just happened?” case? | Yes — past 1h–8h with motion guards and a descriptive-only banner. |
| Does it duplicate History? | Yes — other recorded sleep/rest windows on a day belong in episode-level History detail, not the Now trajectory picker. |
| Does it reintroduce fossil framing? | A label like “last sleep” invites main-sleep vs nap semantics the product has moved past. |

### What we do instead

1. **Default scope** remains `active_sleep_rest` — the active analysis-window
   provenance shared with the current read.
2. **Rolling scopes** cover recent autonomic pattern without claiming recovery
   equivalence.
3. **When a more recent recorded sleep/rest episode exists** than the active
   window (completed `sleep_episode` with start/end, excluding `no_sleep`):
   - Show a **one-off contextual link** in the trajectory dialog only, e.g.
     `Also: rest window 13:40–14:55` — not a permanent menu item.
   - Opening it uses the same recovery trajectory rules for that episode’s
     window; **descriptive only** unless the user later promotes that episode to
     primary via sleep/rest repair (out of scope here).
4. **History day detail** lists all recorded sleep/rest episodes for that date;
   each is tappable for its own recovery-context curve (read-only).

Legacy `episodeKind` values (`main_sleep`, `nap`, etc.) may appear only in
implementer/schema notes when reading `sleep_episode` rows. User-facing copy uses
**sleep/rest window** and **recorded sleep/rest episode** only.

## Scope families

Two families. They must never be conflated in copy or planning logic.

### 1. Recovery trajectory scope (sleep/rest anchored)

Interpret **rest/recovery conditions** inside the active analysis window. This
is the only scope that may feed the autonomic lane participating in planning
state (with existing caveats: descriptive labels, no automatic upgrade to
`GOOD`).

| Scope key | User label | Window definition |
| --- | --- | --- |
| `active_sleep_rest` | **Active sleep/rest** | Start/end from the same **active analysis-window provenance** used by the current read (`lodestone-redesign-architecture.md`). Default and only recovery scope in the selector. |

**Anchor time** for rolling scopes: `min(now, lastSuccessfulPpiSyncAt)` unless
viewing a historic date in History, where anchor is end of that local calendar
day.

### 2. Recent trend scopes (rolling lookback)

Interpret **recent autonomic pattern** over wall-clock time ending at the
anchor. **Descriptive only** — they do not change planning state, hero forecast,
or autonomic strain/momentum on `Now`.

| Scope key | User label | Window definition |
| --- | --- | --- |
| `rolling_1h` | **Past 1 hour** | `[anchor − 1h, anchor]` |
| `rolling_2h` | **Past 2 hours** | `[anchor − 2h, anchor]` |
| `rolling_4h` | **Past 4 hours** | `[anchor − 4h, anchor]` |
| `rolling_8h` | **Past 8 hours** | `[anchor − 8h, anchor]` |
| `rolling_24h` | **Past 24 hours** | `[anchor − 24h, anchor]` |

Rolling scopes may overlap sleep/rest boundaries. `rolling_24h` must render
**sleep/rest vs wake shading** on the chart (see UI contract).

## PPI epoch eligibility

All scopes draw from `ppi247_epoch` rows (not raw batches). Eligibility is
applied in this order:

### Step 1 — Time containment

| Scope family | Rule |
| --- | --- |
| Recovery trajectory | Epoch must satisfy `epochStartEpochMs >= windowStart` **and** `epochEndEpochMs <= windowEnd` (same rule as today). |
| Rolling recent | Epoch must satisfy `epochStartEpochMs >= windowStart` **and** `epochStartEpochMs < windowEnd` (epochs straddling the start boundary may render clipped; exclude from summary stats if &lt; 50% of epoch duration lies inside the window). |

### Step 2 — Quality tiers

**`usable` is still usable** — it counts for summaries alongside `good`, not
only as a chart fallback.

| Tier | Qualities | Chart | Summary stats (avg, p25, early-late) |
| --- | --- | --- | --- |
| **Summary** | `good`, `usable` | Solid (`good`) / dimmer (`usable`) | Yes |
| **Caution** | `review` | Dotted | No — count in quality panel only |
| **Excluded** | `poor_sparse`, `poor_contact_or_error` | Optional faint gap | Never |

RMSSD must be non-null for a point to render.

Minimum counts in Step 4 use **summary epochs** = `good` + `usable`.

### Step 3 — Motion and contact guards (rolling scopes only)

Rolling scopes apply stricter guards because daytime PPI is motion-sensitive:

| Guard | Threshold | Effect |
| --- | --- | --- |
| Movement-heavy epoch | `movementDetectedCount / sampleCount > 0.25` | Exclude from summary stats; show excluded count in quality panel. |
| Poor skin contact | `skinContactFalseCount / sampleCount > 0.20` | Exclude from summary stats. |
| Offline-heavy | `offlineIntervalCount / sampleCount > 0.10` | Exclude from summary stats. |

Recovery trajectory scopes **do not** apply Step 3 initially. Sleep/rest
already implies lower movement; current production behaviour is preserved. Revisit
only if data shows routine contamination.

### Step 4 — Minimum data for a usable view

| View element | Recovery trajectory | Rolling recent |
| --- | --- | --- |
| Show chart | ≥ 3 summary epochs | ≥ 3 summary epochs |
| Show early-late / shape summary | ≥ 12 summary epochs | ≥ 8 summary epochs, labelled **indicative** |
| Show autonomic strain labels | Yes, when ≥ 12 summary epochs | **No** — numeric trend only |
| Empty state | “Not enough clean PPI in this sleep/rest window.” | “Not enough clean PPI in the past N hours.” |

## Quality and provenance display

Every scope view must show a **quality panel** (not generic warnings):

| Field | Meaning |
| --- | --- |
| **Scope** | User label from tables above. |
| **Window** | Local start–end time and duration. |
| **Provenance** (recovery only) | e.g. “Model-selected window”, “User-confirmed sleep/rest episode”. |
| **Summary epochs** | Count of `good` + `usable` epochs in scope. |
| **Review / poor** | Counts by quality tier. |
| **Coverage** | Sum of summary-epoch minutes ÷ window duration (%). |
| **Longest gap** | Longest run without a summary epoch (minutes). |
| **Excluded (motion/contact)** | Rolling scopes only. |
| **PPI freshness** | “PPI synced X min ago”. |
| **Family banner** | Recovery: “Recovery-context HRV — conditions during rest.” Rolling: “Recent trend only — not equivalent to sleep/rest recovery HRV.” |

### Required rolling-scope caveats (always visible)

> This view mixes wake, activity, and rest. It describes recent autonomic
> pattern only. Lodestone does **not** treat it like sleep/rest recovery HRV and
> it does **not** change your daily forecast.

Additional lines when true:

| Condition | Copy |
| --- | --- |
| Coverage &lt; 50% | “Sparse PPI coverage — trend may not represent the full period.” |
| &gt; 30% epochs excluded for motion/contact | “Many windows had movement or poor contact — interpret cautiously.” |
| Window overlaps active sleep/rest | “Includes sleep/rest time — not the same as the active recovery curve.” |
| Anchor &gt; 2h since last PPI sync | “PPI may be stale; check in to refresh.” |

## Planning state influence

Autonomic data may **inform** the daily forecast in the model. It must not be
**surfaced** on `Now` as HRV, PPI, or autonomic labels (see Now surface policy
below).

| Scope | Influences forecast / planning model? | Shown on `Now`? |
| --- | --- | --- |
| `active_sleep_rest` | **Yes** (current behaviour) | **No** — outcome is plain forecast copy only. |
| Contextual other episode link | **No** | **No** |
| All `rolling_*` | **No** | **No** |

Rolling trends must not upgrade/downgrade forecast or replace sleep/rest RMSSD in
history pairing.

## Now surface policy (user direction, 2026-06-12)

`Now` is a **status page**, not an evidence dashboard. HRV, PPI detail,
autonomic labels, trajectory charts, and evidence chips do **not** belong here.

### On `Now` (allowed)

| Element | Rule |
| --- | --- |
| **Daily forecast** | Primary hero label (GOOD / OK / UNSTEADY / CRASH or TBC). |
| **Stability** | Show when supported — short plain-language context, not a full lane breakdown. |
| **PEM / payback risk** | Show when supported; may deserve slightly more space than stability when data exists. |
| **Last sync** | Fine on hero (freshness without opening Signals). |
| **Recent sleep/rest** | Fine on hero — **hybrid 18h summary** (see below). Drill down via `Signals`. |
| **Check in** | Stays as its own section on `Now` — main sync action. |
| **Journal capture** | **Moving onto `Now`**. The separate Journal tab is retired in the target architecture once capture/edit routes live on `Now` and `History`. |

### Off `Now` (Signals tab or History)

- HRV / RMSSD / PPI receipts / autonomic strain-momentum labels
- Trajectory charts and scope selector
- Full sleep/rest provenance, candidate repair, data-quality breakdown
- Evidence chip row and `MorningSignalSection` as it exists today

### What this replaces (current app drift)

Today `DataScreen` still shows `MorningSignalSection` with autonomic context,
functional breakdown, and an **HRV detail** chip — all of that moves to
**`Signals`**. Hero pills today include confidence and full provenance; the new
hero keeps **stability**, **PEM risk**, **last sync**, and **recent sleep** only
when available.

### Model vs surface

| Layer | Autonomic / HRV role |
| --- | --- |
| **Model** | `active_sleep_rest` PPI may still affect forecast, stability, PEM-risk estimates, and mixed-evidence messaging. |
| **Now UI** | Forecast + stability + PEM risk + compact recent sleep/sync — **no HRV numbers or autonomic jargon**. |
| **Signals tab** | Full autonomic detail, scopes, charts, sleep/rest repair, data quality. |
| **History** | Per-day retrospective pairing. |

### Where HRV and autonomic detail live

**Primary home: new `Signals` tab** (working name; alternatives: `Evidence`,
`Body`). User chose this over History-only or a forecast drill-down sheet.

| Surface | Role |
| --- | --- |
| **`Signals` tab** | **Primary live detail.** Scope selector, trajectory chart, quality panel, rolling scopes, 24h shading, sleep/rest episode list + repair, data completeness, PPI freshness. This is where curious users go from `Now`. |
| **History → day detail** | **Retrospective** paired reports, past-day autonomic detail (same scope UI, historic anchor). |
| **Settings → Diagnostics** | Export, rare debug, device tooling — not the main HRV path. |
| **`Now`** | **No HRV.** Compact forecast, stability, PEM risk, recent sleep summary, check-in, journal capture. |

Sleep/rest **repair** moves to **`Signals`**, not `Now`.

### Contextual alternate-episode link

When a more recent recorded sleep/rest episode exists than the active window,
show `Also: rest window 13:40–14:55` in **`Signals` autonomic detail** (episode
list section), never on `Now`.

### Recent sleep on `Now` vs `Signals`

**Now hero — hybrid 18h roll-up** (agreed 2026-06-12):

- **Anchor:** rolling last 18 hours from `now` (or historic date end when viewing
  History).
- **Include:** the **active analysis window** (what the forecast used) **plus**
  any other recorded sleep/rest episodes with time inside that 18h span.
- **Display:** one compact total, e.g. `Recent rest: 6h 40m (last 18h)`.
- **Why:** for regular nights the total ≈ the active window; for **fragmented
  sleep** it sums disjoint episodes so the user sees how much rest they actually
  had without opening `Signals`.
- **Do not** show per-episode breakdown on `Now` — that stays in `Signals`.

**Signals:** full episode list, windows, provenance, per-episode HRV curves.

### `Signals` tab — future lanes (out of scope for `#195`)

Tab name **`Signals`** is confirmed. Grip strength and other monitoring-app
data may land here later. Presentation of external monitoring apps is a separate
design pass — do not block HRV scope work on it.

## UI contract (HRV views — Signals + History, not Now)

### Selector placement

- Scope control on **`Signals` → Autonomic detail** (primary) and **History →
  day detail → Autonomic** (retrospective). Shared chart component.
- **Not** on `Now` or legacy evidence sheets (remove from declutter pass).
- Default scope: `active_sleep_rest`.
- Persist last-selected scope **per session only**.

### Default ordering

1. Active sleep/rest
2. Past 1 hour
3. Past 2 hours
4. Past 4 hours
5. Past 8 hours
6. Past 24 hours

Dropdown or segmented control; prefer dropdown if space is tight.

### Title and copy by family

| Family | Dialog title | Subtitle |
| --- | --- | --- |
| Recovery | `Sleep/rest HRV` | Provenance + duration |
| Rolling | `Recent autonomic trend` | `Past N hours · descriptive only` |

Retire `Overnight HRV trajectory`.

### Chart: sleep/rest vs wake shading (`rolling_24h`)

For `rolling_24h` only (optional for `rolling_8h` if intervals overlap sleep —
implementer may reuse same helper):

- Shade intervals that fall inside **any recorded sleep/rest episode** for the
  span (same `sleep_episode` rows; exclude `no_sleep`) with a calm background
  band (same visual language as `plot_ppi_trajectory.py` sleep shading).
- Unshaded regions = wake / mixed / unknown.
- Legend: `Shaded = recorded sleep/rest · Unshaded = wake or mixed`.
- Shading is **provenance for the eye**, not a claim that shaded HRV equals
  recovery HRV — the rolling banner still applies.

### Chart legend (unchanged semantics)

- Faint line = raw RMSSD (summary epochs)
- Bold line = rolling median
- Straight line = linear trend
- Footer: “Qualitative context, not a diagnosis.”

### `Signals` tab structure (proposed)

1. **Autonomic detail** — scope selector + chart + quality panel (this contract).
2. **Sleep/rest episodes** — list + repair actions (candidate review, edit).
3. **Data completeness** — lane coverage, PPI freshness, missing inputs.
4. Optional link: “How this fed today’s forecast” (plain language, no duplicate
   of Now hero).

### History day detail structure (proposed)

Same autonomic scope UI as `Signals`, anchored to the selected historic date,
plus forecast vs journal outcome pairing.

## Data model sketch (implementation follow-up)

```kotlin
enum class AutonomicDetailScope {
    ACTIVE_SLEEP_REST,
    ROLLING_1H,
    ROLLING_2H,
    ROLLING_4H,
    ROLLING_8H,
    ROLLING_24H,
}

enum class AutonomicScopeFamily { RECOVERY_TRAJECTORY, RECENT_TREND }

enum class AutonomicPlanningInfluence { RECOVERY_LANE, DESCRIPTIVE_ONLY }

data class AutonomicScopeSummary(
    val scope: AutonomicDetailScope,
    val family: AutonomicScopeFamily,
    val planningInfluence: AutonomicPlanningInfluence,
    val windowStartEpochMs: Long,
    val windowEndEpochMs: Long,
    val provenanceLabel: String?,
    val trajectoryPoints: List<HrvTrajectoryPoint>,
    val quality: AutonomicScopeQuality,
    val sleepRestShading: List<ClosedEpochMsRange>?, // rolling_24h
    val alternateEpisodeLink: AlternateEpisodeLink?, // contextual, not a scope
    val cautionBanner: String?,
)

data class AlternateEpisodeLink(
    val episodeId: Long,
    val label: String, // e.g. "Also: rest window 13:40–14:55"
    val startEpochMs: Long,
    val endEpochMs: Long,
)
```

Resolver: `AutonomicScopeResolver.resolve(...)`.

## Follow-up implementation tasks

| # | Task | Depends on |
| --- | --- | --- |
| 1 | **Now declutter** — hero: forecast + stability + PEM risk + last sync + recent sleep summary; check-in section; journal capture on Now; remove evidence chips / HRV / `MorningSignalSection` | — |
| 2 | **App shell** — add `Signals` tab; move sleep/rest repair + autonomic detail there | 1 |
| 3 | Extract generic `summarizePpi247ForScope` | — |
| 4 | Implement `AutonomicScopeResolver` | 3 |
| 5 | `Signals` autonomic detail: scope selector + chart + quality panel | 4, 2 |
| 6 | History day autonomic detail (shared component) | 5 |
| 7 | `rolling_24h` sleep/rest vs wake shading | 5 |
| 8 | Contextual alternate-episode link in `Signals` | 4 |
| 9 | Model keeps autonomic inputs; remove autonomic **surfacing** from `Now` UI only | 1 |
| 10 | Journal stepped dialogue on Now + **retire Journal tab** (same redesign pass) | 1 |
| 11 | Unit tests | 4 |

## Resolved decisions (2026-06-12)

| Topic | Decision |
| --- | --- |
| `last_sleep` scope | **Dropped** from selector; use contextual episode link + History instead. |
| Legacy main sleep / nap | **Not** used in UI or contract language; schema mapping only. |
| `usable` quality | **Included** in summary stats with `good`. |
| 24h chart | **Sleep/rest vs wake shading** required. |
| HRV on `Now` | **No** — `Signals` tab; model may use autonomic data without surfacing HRV on Now. |
| HRV entry point | **`Signals` tab** (new primary evidence tab; Journal is no longer a primary tab). |
| `Now` hero | Forecast + stability (if available) + PEM risk (if available) + last sync + recent sleep summary (~18h coarse). |
| Check in | **Stays** on `Now` as its own section. |
| Journal | **Stepped dialogue on `Now`**; **Journal tab retired** in same redesign pass (`docs/journal-v2-current-state-contract.md`). |
| Tab name | **`Signals`** |
| Recent sleep on Now | **Hybrid:** active window + all recorded sleep/rest in last 18h, one total. |
| Signals future content | Grip / other probes **deferred** — separate monitoring-apps presentation pass. |

## Acceptance mapping (card `#195`)

| Criterion | How this contract meets it |
| --- | --- |
| Separates sleep/rest trajectory from daytime recent trends | Two families + planning table + rolling banner. |
| Quality caveats are concrete | Quality panel + thresholds + conditional copy. |
| Does not equate noisy daytime HRV with recovery HRV | Rolling descriptive-only; motion guards; shading does not remove banner. |
| Implementation deferred | Sketch + follow-up table only. |

---

*Document status: agreed 2026-06-12 (Signals tab, hybrid 18h rest, journal dialogue on Now). Implementation is progressing through the `#256` child-card pass.*
