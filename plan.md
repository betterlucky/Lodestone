# Health Monitor — Review Tab Refresh Plan

## Goal
Make the Review tab a low-friction daily evening ritual. The user should know at a glance whether they still need to act, tap one primary button to save everything, and get clear confirmation that their check-in and food data are stored.

---

## 1. Auto-import food on Save

### Current behaviour
- Food has two manual buttons: "Sync food log" and "Choose file"
- Saving the evening check-in does not touch food data
- The user may forget to sync food, leaving the food card empty

### Proposed behaviour
**Primary change:** tapping **Save** becomes a single action that:
1. Attempts auto-import from the authorised FoodLogData folder for the active `checkInDate`
2. Saves the evening check-in (outcome, approach, muscle weakness, notes)
3. Shows one confirmation covering both results

**Import order:** food import runs *before* the check-in save so the confirmation can include food numbers. The entire flow is wrapped in a single busy state so the button reads "Saving…" throughout.

**Edge cases:**
- If food import succeeds: confirmation says "Saved review + imported food log for 2026-05-01 (1,850 kcal, 6 items)"
- If food import finds no CSV for the selected date: confirmation says "Saved review for 2026-05-01. No food log found for that date — tap Sync food log if you expected one."
- If food import throws an error: evening check-in still saves; error is surfaced inline or in snackbar so the user knows the review itself is safe
- "Choose file" remains as a manual fallback, collapsed into a secondary row under the food summary

**UI consequence:** the two food buttons can be removed from the main card and replaced by a smaller secondary row or collapsed drawer.

---

## 2. Reorder the Review tab into distinct visual zones

### Proposed layout (top to bottom)

| Zone | Purpose | Read/Write |
|---|---|---|
| **Date header** | Shows active date, saved/unsaved state | Read |
| **Morning Signal** | Yesterday’s traffic light + confidence + 2-line reason | Read (reference) |
| **Evening Check-in** | Outcome chips, muscle weakness, approach, notes | Write (primary action) |
| **Save button** | Single primary button, triggers food auto-import + save | Write |
| **Food & Weight** | Collapsed or muted summary; manual sync as fallback | Read / optional write |
| **Recent reviews** | Scannable colour-coded history | Read |

### Why this order
The current layout mixes reading (morning data) with writing (evening entry). Someone with cognitive fatigue should not have to parse metadata before finding the action they came to perform.

---

## 3. Saved-vs-unsaved state on the date header

### Current behaviour
The header shows `"saved review · food synced"` as tiny grey text.

### Proposed behaviour
- **Unsaved:** neutral border, no badge
- **Saved:** green-tinted border + a green "Saved" pill on the right side of the date header
- The status text can shrink to just `"food synced"` or `"no food"` because the saved state is now self-evident

This removes the need for the user to read small status text to know whether their evening ritual is complete.

---

## 4. Make Evening Outcome the hero action

### Changes
- Add a small red `(required)` label beside the outcome prompt
- If Save is tapped with no outcome selected, do not silently fail — shake the chip row or show inline red text under it
- Keep Outcome chips at the top of the primary card; everything else (approach, notes, muscle weakness) stacks beneath with slightly more spacing

---

## 5. Simplify the Morning Read card

### Changes
- If the morning read is **interim** (sleep data not yet ready from Polar), show a muted "Morning data pending" row instead of the full badge + confidence layout
- If ready, surface only: traffic light badge + confidence + up to 2 reason lines
- Move source date, autonomic source, and sleep duration to a detail expander (small "Details ▼" row)
- Remove RMSSD raw number from the card face; the reasons already say what matters (e.g. "RMSSD looked suppressed at 38")

---

## 6. Colour-code the Recent Reviews list

### Changes
- Add a `StatusBadge` pill next to each date (same component used in Morning Read)
- Swap the dense vertical list for a slightly more horizontal/compact summary:
  ```
  2026-05-01   [ Unsteady ]   Approach: OK   Weakness: No
  2026-04-30   [ OK ]         Approach: OK   Weakness: No
  ```
- Keep the full card on tap (already supported via `clickable`)

---

## 7. Improve Muscle Weakness toggle

### Changes
- Replace the small Checkbox with a full-width selectable row using a `Switch`
- Increase vertical padding so the hit area is generous
- Keep the explanatory subtitle but make it smaller

---

## 8. Post-save confirmation

### Changes
- After save succeeds, briefly flash the date header with a success tint (e.g. green border pulse for 1 second). This is visual reinforcement only — the Snackbar remains the primary accessible confirmation.
- Change the Save button label to **"Update"** if the selected date already has a saved review, **"Save"** otherwise. This reacts to the active `checkInDate`, not just today.
- Keep the Snackbar as the authoritative confirmation for accessibility

---

## 9. Reduce visual density

### Changes
- Increase `Arrangement.spacedBy` from `12.dp` to `16.dp` between major cards
- Remove redundant `SupportText` lines where the heading already explains the field (e.g. under "How did the day actually end?", remove "Select the outcome before saving")
- The Food section collapses to a single summary row, which reduces the tallest card significantly

---

## Implementation checklist

| # | Task | File(s) | Effort |
|---|---|---|---|
| 1 | Merge food auto-import into `saveDailyCheckIn()`: import for `checkInDate`, then save check-in, return combined result | `ProbeViewModel.kt`, `DailyReviewRepository.kt` | Small |
| 2 | Reorder `FeedbackScreen` Composables: Morning → Evening → Food | `ProbeApp.kt` | Small |
| 3 | Add saved-state border tint to `ReviewDatePickerField` | `ProbeApp.kt` | Small |
| 4 | Inline validation for Evening Outcome (shake or red text) | `ProbeApp.kt` | Medium |
| 5 | Simplify Morning Read card: interim handling + badge + reasons + expander (also affects `DataScreen`) | `ProbeApp.kt` | Small |
| 6 | Add `StatusBadge` to Recent Reviews list items | `ProbeApp.kt` | Small |
| 7 | Replace Muscle Weakness checkbox with full-width Switch row | `ProbeApp.kt` | Small |
| 8 | Post-save success flash on date header + Save/Update label logic | `ProbeApp.kt` | Small |
| 9 | Increase section spacing and prune redundant support text | `ProbeApp.kt` | Small |

---

## Out of scope (for this plan)

- Changing the morning scoring model (`deriveMorningRead`) — the plan only resurfaces existing data
- New tabs or navigation changes
- Daytime monitoring or foreground service work
- DB schema changes — all data needed already exists

---

## Success criteria

- A user can open the Review tab, pick today, select an outcome, tap Save once, and see confirmation that both check-in and food are stored
- A user can glance at the date header and know whether today is already saved
- A user can scan Recent Reviews and see colour-coded outcomes without reading labels
- The screen feels less dense and the primary action is visually dominant
