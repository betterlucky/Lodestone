# Exertional Load Analysis

This note records the current Lodestone treatment of exertional load (EL) and
the repeatable analysis script used to explore it.

## Purpose

EL is what the user actually incurred: steps, MET minutes, activity calories,
site visits, leaving the house, work, grip-test burden, and other exertional or
participation demands.

EL is not the same thing as objective function (OF). Activity can be high
because the user had capacity, because they were forced into activity, or
because they overreached. Activity can be low because they were pacing, because
they could not do more, or because the day required little. Treat EL as
load/context and possible PEM-trigger evidence until it is paired with later
outcomes.

## Report Script

Run:

```bash
python3 scripts/exertional_load_report.py --health-db /path/to/health-monitor-probe.db
```

Optional JSON:

```bash
python3 scripts/exertional_load_report.py --health-db /path/to/health-monitor-probe.db --json > /tmp/exertional-load.json
```

Optional date window:

```bash
python3 scripts/exertional_load_report.py \
  --health-db /path/to/health-monitor-probe.db \
  --start-date 2026-05-01 \
  --end-date 2026-06-08
```

The script is read-only. It combines:

- `daily_summary_raw` for whole-day steps, activity calories, achieved activity,
  and distance.
- `activity_epoch` for coverage, average MET, MET >= 1.5 minutes, and
  MET >= 2.0 minutes.
- `daily_check_in` for SF outcomes, approach-to-day, muscle weakness, PEM/payback,
  day-shape, and reported activity/load markers.
- `morning_prediction_snapshot` for saved status snapshots.
- `current_state_model_report.py`, when available, for derived planning,
  autonomic, functional, stability, and autonomic-context labels.

## Features

The report currently calculates:

- daily EL: steps, activity calories, achieved activity, MET features, coverage
- rolling EL: 2-day, 3-day, and 7-day step sums
- load spikes: current steps vs prior 7-day median
- local delta: current steps vs prior 3-day median
- EL -> outcome relationships for D0, D+1, D+2, and D+3
- state -> activity relationships for same-day and next-day steps
- grouped step summaries by outcome, approach, planning status, autonomic
  context, day-shape markers, and reported activity markers

## Current Interpretation

As of the first durable report pass on 2026-06-08:

- There were enough paired activity/outcome days for a directional signal, but
  not enough for careful tuning.
- Same-day EL had the clearest relationship with worse SF outcome. This is
  expected because EL can be a same-day strain/context signal.
- D+1 EL effects were weak.
- D+2 EL effects were more interesting, especially achieved activity,
  MET >= 2.0 minutes, and step spikes vs recent baseline, but still too fragile
  for model promotion.
- Reverse-direction prediction is behavior under constraints, not OF. Planning
  state or autonomic state may predict what the user actually does, but that
  does not prove safe capacity.

Use this as an analysis/research layer for now. Do not put EL directly into the
production planning state as a status-changing feature until the D+2/load-spike
signals survive more data and preferably line up with PEM/payback, grip, or
other objective-function probes.

## Practical Use

Good next uses:

- Rerun after each meaningful data pull.
- Compare whether D+2 associations strengthen as activity coverage grows.
- Watch load-spike features more closely than raw absolute steps.
- Treat site visits, left-house markers, and major tasks as reported EL/context
  features, not SF labels.
- Use EL as explanatory context in UI before using it as a headline model input.

Avoid:

- turning steps into a load budget from this data volume
- treating low activity as low OF
- treating high activity as high OF
- assuming a same-day correlation proves causality
