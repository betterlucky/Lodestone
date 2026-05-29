# Candidate Review Contract

This note defines the first candidate-review surface for Check in and Catch up.
It is a product and implementation contract for the Candidate Review 1-8 work,
not a claim that the current model is proven.

## Goals

- Show sleep/rest windows inferred from Check in and Catch up without treating
  them as facts.
- Distinguish inferred candidates from user-confirmed or edited episodes.
- Give the user short repair choices: accept, edit, reject, mark rest, or mark
  no main sleep.
- Keep readiness conservative: a candidate does not become the primary
  readiness window until the user confirms or explicitly selects it.
- Preserve the freshness rule: Catch up is based on stale readiness and Loop
  sync data, not on whether the app was recently opened or a review was edited.

## Data Contract

The UI should read `sleep_episode` rows for the active `sourceDate` and, for
Catch up, for each repair date in the catch-up range.

| Field | UI meaning |
| --- | --- |
| `sourceDate` | The Lodestone day being repaired. A window can start on the previous clock day. |
| `startEpochMs`, `endEpochMs` | Display range and duration. Both can be null for a no-sleep marker. |
| `episodeKind` | `main_sleep`, `nap`, `rest_candidate`, or `no_sleep`. |
| `source` | `ppi_inferred`, `manual`, `edited`, `polar_sleep`, or `mixed`. |
| `confidence` | `low`, `medium`, `high`, or `user_confirmed`. |
| `isPrimaryForReadiness` | Whether this episode is allowed to drive the main readiness window. |
| `evidenceJson` | Short evidence summary, for example inferred source, duration, and wake-marker presence. |
| `notes` | User-facing repair note when present. |

Candidate rows are rows with `source = ppi_inferred` and
`confidence != user_confirmed`. They must display as suggestions and must keep
`isPrimaryForReadiness = false`.

Confirmed rows are rows with `confidence = user_confirmed` or an explicit
manual/edited source. A confirmed `main_sleep` row may become primary. A
confirmed `nap` or rest row remains context unless the user explicitly promotes
it in a later primary-selection flow.

A no-sleep day is represented as `episodeKind = no_sleep`,
`confidence = user_confirmed`, null start/end times, and
`isPrimaryForReadiness = false`. It records that the user deliberately chose not
to fabricate a main sleep window for that source date.

## Entry Points

Check in refreshes candidates for the current Lodestone date, then surfaces a
candidate-review affordance when any of these are true:

- inferred candidates exist for today
- a no-sleep or confirmed episode already exists and should be visible
- there are no candidates but readiness still lacks a confirmed sleep/no-sleep
  decision

Catch up refreshes candidates for the stale readiness range, capped by the
existing catch-up date limit. It should present date rows for days that need
repair, not a single undifferentiated candidate list.

`Last used` is only a recency hint about app interaction, markers, reviews, or
syncs. It must not suppress Catch up. The Catch up prompt is driven by stale
readiness dates and stale or missing Loop readiness sync data.

## Surface States

| State | Trigger | Required copy |
| --- | --- | --- |
| Loading | Candidate refresh or episode fetch in progress. | `Checking recent Loop data...` |
| Candidates | One or more inferred rows are present. | `Review possible sleep/rest windows before using them for readiness.` |
| Confirmed | A user-confirmed main sleep, nap, rest, or no-sleep row exists. | `This day has your confirmed sleep/rest decision.` |
| Empty | No inferred or confirmed rows exist for the date. | `No sleep/rest candidates found yet.` |
| Error | Fetch or action failed. | `Candidate review could not update. Existing data was left unchanged.` |
| Catch-up list | Multiple stale dates need attention. | `Review missing days from oldest to newest.` |

The empty state is not an alarm. It should explain the next useful action:
Check in again after the Loop has more data, mark no main sleep, or wait for the
final Loop report.

## Episode Copy

Use these labels consistently in rows, dialogs, status messages, and tests.

| Episode state | Condition | Title | Supporting copy |
| --- | --- | --- | --- |
| Possible sleep | `ppi_inferred` candidate with `episodeKind = main_sleep`. | `Possible sleep` | `Lodestone found a quiet low-movement window. Confirm or edit it before it drives readiness.` |
| Possible nap | `ppi_inferred` candidate that the user is reviewing as a nap-sized sleep episode. | `Possible nap` | `This may be a nap or short sleep. Keep it as context unless you choose otherwise.` |
| Rest-like | `ppi_inferred` candidate with `episodeKind = rest_candidate`. | `Rest-like window` | `The signal looks restful, but Lodestone should not call it sleep without your review.` |
| No sleep | Confirmed `episodeKind = no_sleep`. | `No main sleep` | `You marked this day as having no main sleep window. Readiness should stay cautious rather than inventing one.` |
| Confirmed sleep | Confirmed `episodeKind = main_sleep`. | `Confirmed sleep` | `This sleep window can be used for the main readiness read.` |
| Confirmed nap | Confirmed `episodeKind = nap`. | `Confirmed nap` | `This stays as context unless you select it as the readiness window.` |
| Confirmed rest | Confirmed rest/non-sleep row. | `Confirmed rest` | `This was useful rest, but not the main sleep window.` |
| Edited episode | `source = edited`. | `Edited sleep/rest` | `You adjusted this window. Lodestone keeps the raw evidence separately.` |
| Final Loop report | `source = polar_sleep` or linked final sleep data. | `Final Loop sleep report` | `Vendor sleep is supporting context; user-confirmed local episodes decide the primary readiness window.` |

Confidence badges should be short and humble:

- `low`: `Low signal`
- `medium`: `Medium signal`
- `high`: `High signal`
- `user_confirmed`: `Confirmed`

Source badges should use:

- `ppi_inferred`: `Suggested from PPI`
- `manual`: `Manual`
- `edited`: `Edited`
- `polar_sleep`: `Loop report`
- `mixed`: `Mixed evidence`

## Actions

Candidate Review 1 only defines these actions; later tasks wire them.

| Action | Result |
| --- | --- |
| Accept as main sleep | Creates or updates a confirmed `main_sleep` row and can set `isPrimaryForReadiness = true`. |
| Accept as nap | Creates or updates a confirmed `nap` row with `isPrimaryForReadiness = false`. |
| Mark as rest | Keeps the window as confirmed rest/non-sleep context, not primary readiness input. |
| Edit window | Lets the user adjust start/end before confirming; `source` becomes `edited` or `mixed`. |
| Reject suggestion | Prevents the same inferred row from appearing as accepted fact. |
| Mark no main sleep | Stores a confirmed `no_sleep` row for the date with no fabricated window. |

Action copy should avoid medical certainty. Prefer `Use this window`,
`Edit`, `Not sleep`, and `No main sleep` over stronger diagnostic language.

## Readiness Rules

- Inferred candidates are never primary readiness inputs by default.
- A confirmed main sleep row can be primary for readiness.
- A confirmed nap or rest row is context unless explicitly selected later.
- A no-sleep marker blocks fabricated sleep for that source date.
- If there is no primary confirmed episode and no final sleep report, the
  morning read should remain provisional or TBC.
- Final Loop sleep may inform the evidence summary, but it does not silently
  override a user-confirmed local repair.

## Display Details

- Sort date groups from oldest to newest in Catch up so repair proceeds in the
  same order the model will later learn from.
- Sort rows within a date by `startEpochMs`, then `id`; no-sleep rows appear
  after timed rows.
- Show local time range, duration, kind, source badge, confidence badge, primary
  badge, and a one-line evidence summary.
- A primary badge should read `Readiness window`.
- Do not show stale candidate counts after an action; refresh the date group.
- Keep Check in compact. The Today screen should show one affordance and a count,
  while details can live in an expandable section or dialog.

## Acceptance Criteria For This Slice

- The contract defines the candidate, confirmed, no-sleep, loading, empty, and
  error states.
- The contract specifies user-facing copy for possible sleep, possible nap,
  rest-like, no sleep, and user-confirmed episodes.
- The contract states that Catch up is based on stale readiness/Loop sync data,
  not Last used.
- The contract preserves conservative readiness behavior for inferred windows.
