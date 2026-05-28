# Lodestone Project Context

This note captures project-specific context that is easy to lose between sessions. It is deliberately practical rather than exhaustive: the aim is to preserve the quirks, decisions, and working assumptions that affect future implementation.

Treat this document as a snapshot and guidance aid, not the source of truth. If it conflicts with the current code, committed docs, database schema, or explicit user instructions, prefer those sources and update this note when the dust settles.

## Product Shape

- Lodestone is a personal health-monitoring prototype for daily pacing, recovery tracking, and current-condition reflection.
- The app is currently Android-first and Kotlin/Compose-based.
- The primary daily flow is intended to be low-friction:
  - Check in / sync / readiness read
  - optional explicit sleep and wake markers
  - Catch up when stale syncs, missed markers, or unresolved sleep/rest candidates need repair
  - optional food-log import
  - evening review with outcome, approach-to-day, notes, and muscle weakness flag
- The app should avoid becoming a general quantified-self dashboard unless a metric has plausible pacing/recovery value.
- Personal-use reliability matters more than public-release polish right now, but avoid painting the codebase into a private-only corner where possible.

## Current Data Strategy

- Polar Loop / Polar 360-class device is the main target device.
- Garmin is currently a secondary/side data source and may be phased out if Polar proves sufficient.
- H10 is for calibration/testing rather than normal daily use.
- Food logs are imported from a separate Food Log workflow/app rather than entered directly into Lodestone.
- Health Connect export support is analysis-only for now, mainly to test whether Sleep2/H10 data can be retrieved.

## Polar Loop Findings

- The Loop can expose useful `PPI_247` data, but availability has appeared inconsistent across sync timing/device state.
- Do not assume missing PPI means the device cannot record it; first check sync timing, date windows, and whether Flow/device processing has completed.
- Sleep and Nightly Recharge data may appear after device-side processing delay.
- Polar Sleep is now best treated as supporting/vendor context rather than the
  canonical readiness gate. Raw PPI plus manual or inferred sleep/rest episodes
  should become the primary local readiness basis.
- Flow sync often appears to make sleep reports available, but at least one test suggested Lodestone-only sync can eventually retrieve resolved sleep data too.
- The current hypothesis is not fully settled:
  - the Loop likely does some processing locally
  - Flow may trigger, assist, or reveal finalisation
  - device storage pressure may have contributed to earlier weirdness
- Keep Flow in the loop for now, especially for firmware updates and sleep-report finalisation checks.
- Flow competes for the single BLE connection, so Lodestone and Flow cannot reliably coexist while both want the Loop.
- Preferred user workflow remains:
  - restrict/disable Flow Bluetooth while Lodestone is doing its work
  - use explicit `Prepare for Flow` handoff when syncing Flow manually
  - close Flow / disable its Bluetooth access before returning to Lodestone

## Polar Data Lanes

- `PPI_247` belongs in the real autonomic lane if present for accepted or
  inferred sleep/rest windows.
- `HR_247` is useful context but is not a substitute for PPI/HRV.
- Nightly Recharge remains useful as a semi-derived supporting autonomic lane.
- Sleep structure, vendor sleep timing, respiration, skin temperature, and
  previous-day context are useful supporting lanes.
- Offline PPI/PPG recording was valuable as an investigation route, but is not part of the normal workflow now.
- Offline recording docs and code may be archived for future reference, but should not clutter the live daily UI.

## Device Storage And Maintenance

- The Loop appears to retain multiple days of onboard data.
- If Lodestone reduces reliance on Flow, it may need to manage old device history itself.
- Safe maintenance principle:
  - only delete device history for dates already archived locally
  - retain a recent window, currently around 14 days
  - never delete today's or recent unresolved data
- Old offline PPG/PPI files from experiments are no longer important, but deleting stale offline files is harmless as a safety net.
- The more important long-term maintenance question is normal Loop stored data, not old offline test files.

## Garmin Context

- Garmin data has been useful for comparison, especially overnight HRV trajectory, HR, respiration, SpO2, stress, Body Battery, and sleep.
- Garmin sleep detection can be poor for low-arousal wakefulness and irregular sleep.
- Garmin may finalise sleep late or misplace wake time.
- Garmin Connect/givemydata access is brittle:
  - browser automation is janky on macOS
  - permissions and Chrome profile/full-disk-access issues recur
  - Garmin rate limits can block collection
- There is a `docs/garmin-sidecar-plan.md`; keep Garmin as sidecar unless we later decide it is worth deeper integration.
- Do not rely on Garmin black-box stress/Body Battery as truth; treat them as possibly useful derived context.

## H10 / Sleep2 / Health Connect

- H10 is expected to be used for:
  - calibration of PPI/HRV accuracy
  - checking sleep-window/stage disagreement
  - possible lean/POTS-style tests
- Sleep2 + H10 screenshot data showed HRV broadly agreeing with Loop PPI, but sleep duration/window/staging differed materially.
- Lodestone can now request/read Health Connect sleep, heart rate, and HRV permissions.
- A Health Connect export successfully produced data, but the observed records were Garmin-origin only.
- Health Connect supports multiple sleep records/origins, so Garmin/Flow do not appear to crowd out Sleep2.
- Current suspicion:
  - Sleep2 either did not write the H10 session to Health Connect
  - or writes only internal analysis / image export rather than standard HC sleep/HR/HRV records
- Sleep2 screenshot imports are calibration-only and live outside the main DB.
- App import location: `/sdcard/Android/data/com.daveharris.healthmonitor/files/analysis-sleep2/screenshots/`.
- Imported Sleep2 screenshots are renamed to `sleep2-statistics-YYYY-MM-DD.png` with a same-date JSON sidecar.
- The import uses Lodestone's selected review date as the calibration date, so check the Review date before importing backdated screenshots.
- Next low-cost Sleep2 test:
  - force-stop Sleep2 before a recording night
  - confirm sharing settings
  - record with H10
  - next day export Health Connect and inspect record origins

## Food Log Context

- Food logging is intentionally separate from Lodestone.
- Lodestone imports exported CSVs, usually from a `FoodLogData` folder because Android blocks frictionless access to the main Downloads folder.
- Current food files are named like `food_log_yyyy-mm-dd`.
- Only import files matching the selected review date.
- Importing food for a date must not reset existing review ratings/notes.
- Reset on the review screen should reset the current draft/import state, not delete source CSVs.
- Newer food-log format may include a weight row tagged as weight.
- Height may eventually live in user settings; current personal test height noted previously as 5ft11, but avoid hard-coding this into production logic without settings.
- Nutrient expansion is possible later because the food log contains item/amount detail and can be retrospectively enriched.

## Review / Daily Rating Model

- Morning prediction and evening outcome are distinct.
- Evening outcome remains the more meaningful subjective label for training.
- `approach_to_day` helps explain mismatches by separating condition from pacing behaviour.
- Muscle weakness is tracked separately because it may be rare, missable, and physiologically distinct from general fatigue/brain fog.
- Future grip-strength measurements may become a useful first/last thing objective marker, but are not integrated yet.
- User-selected date should be the single source of truth for the review screen.

## Current Model Direction

- The model is still prototype/deterministic and should be presented as interim.
- Do not claim the model “works” until enough paired daily prediction/outcome data exists.
- Current useful predictors likely include:
  - overnight PPI/RMSSD trajectory
  - sleep duration and timing
  - resting/overnight HR
  - respiration
  - skin temperature deviations
  - previous-day load/context
  - subjective outcome history
  - possibly food/calories/caffeine later
- Derived vendor scores can be useful but should not be treated as transparent ground truth.
- Parallel model families remain a good future idea:
  - stable deterministic default
  - adaptive/personalised candidate
  - comparison-only variants
- Any adaptive model should be reversible/resettable and should not silently rewrite the user's baseline.

## UI / UX Direction

- The app has moved from experimental probe toward a calmer daily-use prototype.
- Visual identity is currently blue/calm, with the name `Lodestone` being tried.
- The dark theme worked for end-of-day review but can feel gloomy for daytime use; revisit colour scheme later.
- Review screen should be clear about active date and whether food is synced.
- Settings should stay out of the main tab flow where possible.
- Sync tab may eventually be removed if all sync actions are handled contextually.
- Swipe navigation uses deliberate/resistant paging to avoid accidental tab changes.
- Post-swipe tap blocking exists to avoid accidental button activation.

## Bluetooth / Flow Constraints

- Loop-class Polar hardware appears to allow only one BLE central connection at a time.
- Android cannot force another app's BLE connection to disconnect.
- A public app cannot programmatically revoke Polar Flow's Bluetooth permission.
- Practical strategies:
  - user restricts Flow background/Bluetooth access
  - Lodestone offers clear handoff to Flow
  - Lodestone retries connection gracefully
  - avoid promising seamless Flow coexistence
- Shizuku/ADB-style permission toggling is possible only as a personal-device hack, not public-app-safe.

## External Device Options Considered

- Oura may offer useful 5-minute RMSSD-style sleep data but no raw beat-level public feed.
- Fibion/Helix appears interesting and possibly Loop-like hardware with different firmware, but availability/SDK claims need verification.
- Visible Band may be a cheap experimentation route for alternative Polar-like firmware/hardware behaviour.
- PineTime/PineTime Pro is a future lead as an ultra-open hackable wearable/marker-device platform, but the current model should not be treated as a serious HRV/PPI sensor candidate.
- Corsano appears research/HCP-oriented and less directly accessible than hoped.
- H10/ECG patch are good calibration tools but not realistic all-day wear for this project.
- The broader wearable market is frustrating: many devices could expose 24/7 HRV/PPI, but most deliberately gate, smooth, or withhold it.

## Data Safety

- Never commit:
  - `.env`
  - Polar cloud credentials/tokens
  - Garmin credentials/sessions
  - raw pulled phone databases
  - personal health exports
  - large analysis JSONs containing personal samples
- Use `/tmp` for transient DB pulls, plots, and analysis artifacts unless explicitly asked to preserve them.
- Be especially careful before DB resets or migrations; the user has explicitly said they do not want accidental data loss.

## Agent / Workflow Context

- Read `docs/agent-playbook.md` before substantial work.
- Use local Qwen sidecar only for bounded read-only helper tasks; never pass secrets or personal raw data.
- Main agent should keep ownership of:
  - health model design
  - BLE/SDK interpretation
  - database safety
  - final patch integration
  - verification
- User is rate-limit constrained, so prefer targeted reads, concise summaries, and small verifiable patches.

## Near-Term Next Checks

- Confirm next pure Lodestone Check in sync behaviour without Flow interference.
- Continue watching whether Loop PPI/sleep data appears reliably after processing delay.
- Run another Health Connect export after force-stopping Sleep2 before an H10 night.
- Decide whether Sleep2 data is retrievable enough to matter or only useful as screenshot/manual calibration.
- Keep collecting paired readiness/evening subjective ratings before revisiting model weights seriously.
- Revisit UX polish when data collection feels stable.
