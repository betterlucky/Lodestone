# Codex Handover

This note preserves useful context from older Codex project threads that may stay attached to the previous `Documents/HealthMonitor` project entry. It is not the source of truth. Prefer current code, `README.md`, `LodestoneContext.md`, `docs/agent-playbook.md`, and explicit user instructions when they disagree.

## Current Project Shape

- The project is now named Lodestone, though the repository folder is still `HealthMonitor`.
- Lodestone is an Android-first personal health-monitoring prototype for pacing, recovery tracking, and morning/evening reflection.
- It is a research prototype, not a medical device or consumer-ready health product.
- The primary use case is ME/CFS pacing support: a morning signal should help the user decide how cautiously to approach the day, while evening labels provide the more meaningful outcome signal for later model tuning.
- The project is local-first and privacy-sensitive. Treat health exports, pulled databases, Polar/Garmin credentials, cloud data, and calibration captures as sensitive.

## Core Decisions From Prior Threads

- **Morning prediction and evening outcome are distinct.** Morning status is decision support; evening outcome is the label to learn from.
- **Do not overclaim.** The model is still provisional/deterministic. Do not present traffic-light output as proven until enough paired daily prediction/outcome data exists.
- **Polar Loop/Polar 360 is the main target.** Garmin is useful as a comparison sidecar but is brittle enough that it should not drive the main architecture unless explicitly reopened.
- **Raw PPI matters.** `PPI_247` is the important autonomic lane when present. `HR_247`, Nightly Recharge, sleep report data, respiration, skin temperature, activity context, and subjective labels are supporting lanes.
- **Flow handoff is practical reality.** Polar Flow competes for the single BLE connection. Lodestone should provide clear handoff/retry behaviour rather than promising seamless coexistence.
- **Food stays separate.** FoodLog owns food capture. Lodestone imports daily CSVs and weight rows from the FoodLog workflow.
- **User-selected date matters.** Review/import/reset behaviour should respect the active review date and should not accidentally rewrite ratings or notes for another date.

## Current Research Memory

- Polar sleep and Nightly Recharge finalisation can lag wake time. A useful UX should show a provisional morning read before final vendor sleep data arrives.
- Provisional sleep windows based on bedtime/wake markers plus low-motion HR drop are promising, but still need real-world validation.
- Lodestone-only sync may eventually retrieve resolved sleep data, but Flow may still trigger or reveal finalisation. Keep the hypothesis open.
- Garmin Connect/givemydata access is fragile on macOS because of browser automation, permissions, Chrome profile/driver issues, and possible rate limits.
- H10/Sleep2/Health Connect are calibration routes, not normal daily workflow. Sleep2 screenshot import uses the selected review date, so backdated imports need care.
- The visible short-term need is more paired data, not a smarter-looking model.

## UX And Workflow Direction

- Keep the daily flow low friction: bedtime marker, wake marker/sync, morning read, optional food import, evening review.
- Avoid turning Lodestone into a generic quantified-self dashboard. Metrics should earn their place by helping pacing/recovery interpretation.
- The dark theme worked for evening review but may feel gloomy for daytime use; colour/visual polish can be revisited once data collection is stable.
- Settings should stay out of the main tab flow where possible.
- Swipe navigation should stay deliberate/resistant enough to avoid accidental tab changes.
- Sync UI can be simplified once the data collection story is stable.

## Safety Notes

- Do not commit `.env`, Polar cloud credentials/tokens, Garmin credentials/sessions, raw pulled phone DBs, health exports, calibration screenshots, or large personal sample JSON.
- Use `/tmp` for transient DB pulls, plots, and analysis artifacts unless the user asks to preserve them.
- Be careful before DB resets, migrations, or cleanup scripts. The user has explicitly said they do not want accidental data loss.
- For local-model sidecars, keep prompts bounded and do not pass secrets or personal raw health data.

## Useful Starting Points

- Read `docs/agent-playbook.md` before substantial work.
- Read `LodestoneContext.md` for the living project context snapshot.
- Read `docs/polling-and-monitoring-decisions.md` for sync/polling choices.
- Read `docs/offline-recording-archive.md` before touching old offline recording logic.
- Read `docs/polar-cloud-backfill-probe.md` before reviving Polar cloud probing.
- Read `docs/garmin-sidecar-plan.md` before reviving Garmin collection.

## Old Codex Thread Index

These old threads are useful background if they remain visible under the previous Codex project entry:

- Initial Polar Loop / Polar 360 implementation plan and data-source exploration.
- Health Monitor data tracker work.
- DB compaction / Lodestone storage troubleshooting on the attached phone.
- Polar/Flow sync behaviour and provisional morning signal discussion.
- Garmin sidecar and Chrome/Selenium reliability discussion.
- FoodLog import and weight-row contract discussion.

