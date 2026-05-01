#!/usr/bin/env python3

from __future__ import annotations

import argparse
import html
import json
import sqlite3
from datetime import datetime
from pathlib import Path
from typing import Any


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Plot Polar PPI_247 RMSSD/HR trajectory, optionally over Garmin HRV.")
    parser.add_argument("--health-db", required=True, help="Path to the Lodestone/HealthMonitor SQLite database.")
    parser.add_argument("--date", required=True, help="Source date, YYYY-MM-DD.")
    parser.add_argument("--garmin-db", help="Optional Garmin givemydata SQLite database.")
    parser.add_argument("--out", required=True, help="Output SVG path.")
    return parser.parse_args()


def connect(path: str) -> sqlite3.Connection:
    conn = sqlite3.connect(path)
    conn.row_factory = sqlite3.Row
    return conn


def parse_iso(value: str | None) -> datetime | None:
    if not value:
        return None
    try:
        return datetime.fromisoformat(value)
    except ValueError:
        return None


def local_dt_from_epoch_ms(value: int) -> datetime:
    return datetime.fromtimestamp(value / 1000).astimezone()


def sleep_window(conn: sqlite3.Connection, source_date: str) -> tuple[datetime | None, datetime | None]:
    row = conn.execute(
        "select rawPayloadJson from sleep_night_raw where sourceDate = ? order by syncTimestampEpochMs desc limit 1",
        (source_date,),
    ).fetchone()
    if not row:
        return None, None
    payload = json.loads(row["rawPayloadJson"])
    result = payload.get("result") or {}
    return parse_iso(result.get("sleepStartTime")), parse_iso(result.get("sleepEndTime"))


def polar_points(conn: sqlite3.Connection, source_date: str) -> list[dict[str, Any]]:
    rows = conn.execute(
        """
        select epochStartEpochMs, rmssdMs, meanHrBpm, epochQuality
        from ppi247_epoch
        where sourceDate = ?
        order by epochStartEpochMs
        """,
        (source_date,),
    ).fetchall()
    return [
        {
            "time": local_dt_from_epoch_ms(row["epochStartEpochMs"]),
            "rmssd": row["rmssdMs"],
            "hr": row["meanHrBpm"],
            "quality": row["epochQuality"],
        }
        for row in rows
        if row["rmssdMs"] is not None
    ]


def garmin_points(path: str | None, source_date: str) -> list[dict[str, Any]]:
    if not path or not Path(path).exists():
        return []
    conn = connect(path)
    try:
        row = conn.execute("select raw_json from hrv_timeline where calendar_date = ?", (source_date,)).fetchone()
        if not row:
            return []
        payload = json.loads(row["raw_json"])
        points = []
        for item in payload.get("hrvReadings") or []:
            parsed = parse_iso(item.get("readingTimeLocal"))
            if parsed and item.get("hrvValue") is not None:
                points.append({"time": parsed, "hrv": float(item["hrvValue"])})
        return points
    finally:
        conn.close()


def scale(value: float, low: float, high: float, size: float, invert: bool = False) -> float:
    if high <= low:
        return size / 2
    position = (value - low) / (high - low) * size
    return size - position if invert else position


def polyline(points: list[tuple[float, float]], color: str, width: int = 2) -> str:
    if not points:
        return ""
    values = " ".join(f"{x:.1f},{y:.1f}" for x, y in points)
    return f'<polyline points="{values}" fill="none" stroke="{color}" stroke-width="{width}" stroke-linejoin="round" stroke-linecap="round" />'


def render_svg(
    source_date: str,
    polar: list[dict[str, Any]],
    garmin: list[dict[str, Any]],
    sleep_start: datetime | None,
    sleep_end: datetime | None,
) -> str:
    width, height = 1200, 700
    margin_left, margin_right, margin_top, margin_bottom = 70, 40, 70, 70
    plot_w = width - margin_left - margin_right
    top_h = 360
    bottom_y = margin_top + top_h + 70
    bottom_h = height - bottom_y - margin_bottom

    local_tz = next((p["time"].tzinfo for p in polar if p["time"].tzinfo is not None), None)

    def comparable(dt: datetime) -> datetime:
        if dt.tzinfo is None and local_tz is not None:
            return dt.replace(tzinfo=local_tz)
        return dt

    times = [comparable(p["time"]) for p in polar] + [comparable(p["time"]) for p in garmin]
    if sleep_start:
        times.append(comparable(sleep_start))
    if sleep_end:
        times.append(comparable(sleep_end))
    x_min, x_max = min(times), max(times)
    x_span = max((x_max - x_min).total_seconds(), 1)

    def x_for(dt: datetime) -> float:
        dt = comparable(dt)
        return margin_left + (dt - x_min).total_seconds() / x_span * plot_w

    rmssd_values = [p["rmssd"] for p in polar if p.get("rmssd") is not None] + [p["hrv"] for p in garmin if p.get("hrv") is not None]
    hr_values = [p["hr"] for p in polar if p.get("hr") is not None]
    rmssd_min, rmssd_max = max(0, min(rmssd_values) - 10), max(rmssd_values) + 10
    hr_min, hr_max = max(30, min(hr_values) - 5), max(hr_values) + 5

    polar_line = [
        (x_for(p["time"]), margin_top + scale(float(p["rmssd"]), rmssd_min, rmssd_max, top_h, invert=True))
        for p in polar
        if p.get("rmssd") is not None
    ]
    garmin_line = [
        (x_for(p["time"]), margin_top + scale(float(p["hrv"]), rmssd_min, rmssd_max, top_h, invert=True))
        for p in garmin
        if p.get("hrv") is not None
    ]
    hr_line = [
        (x_for(p["time"]), bottom_y + scale(float(p["hr"]), hr_min, hr_max, bottom_h, invert=True))
        for p in polar
        if p.get("hr") is not None
    ]

    sleep_rect = ""
    if sleep_start and sleep_end:
        x1, x2 = x_for(sleep_start), x_for(sleep_end)
        sleep_rect = f'<rect x="{x1:.1f}" y="{margin_top}" width="{max(x2 - x1, 0):.1f}" height="{top_h + 70 + bottom_h}" fill="#dbeafe" opacity="0.45" />'

    ticks = []
    for hour in range(x_min.hour, x_max.hour + 2):
        tick = x_min.replace(hour=hour % 24, minute=0, second=0, microsecond=0)
        if tick < x_min:
            continue
        x = x_for(tick)
        ticks.append(f'<line x1="{x:.1f}" y1="{margin_top}" x2="{x:.1f}" y2="{height - margin_bottom}" stroke="#e2e8f0" stroke-width="1" />')
        ticks.append(f'<text x="{x:.1f}" y="{height - 32}" font-size="13" fill="#475569" text-anchor="middle">{tick.strftime("%H:%M")}</text>')

    quality_counts: dict[str, int] = {}
    for point in polar:
        quality_counts[point["quality"]] = quality_counts.get(point["quality"], 0) + 1
    quality_text = ", ".join(f"{key}: {value}" for key, value in sorted(quality_counts.items()))

    return f"""<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">
  <rect width="100%" height="100%" fill="#f8fafc" />
  <text x="{margin_left}" y="34" font-size="24" font-family="Avenir, Helvetica, Arial, sans-serif" fill="#0f172a" font-weight="700">Polar PPI trajectory vs Garmin HRV, {html.escape(source_date)}</text>
  <text x="{margin_left}" y="56" font-size="14" font-family="Avenir, Helvetica, Arial, sans-serif" fill="#475569">Blue shaded region is the Polar sleep window. Polar points are 5-minute PPI_247 epochs.</text>
  {sleep_rect}
  {''.join(ticks)}
  <line x1="{margin_left}" y1="{margin_top}" x2="{margin_left}" y2="{margin_top + top_h}" stroke="#334155" />
  <line x1="{margin_left}" y1="{margin_top + top_h}" x2="{width - margin_right}" y2="{margin_top + top_h}" stroke="#334155" />
  <text x="22" y="{margin_top + 18}" font-size="13" fill="#334155" transform="rotate(-90 22,{margin_top + 18})">RMSSD / HRV ms</text>
  {polyline(polar_line, "#2563eb", 3)}
  {polyline(garmin_line, "#f97316", 3)}
  <text x="{margin_left}" y="{margin_top + top_h + 28}" font-size="14" fill="#2563eb">Polar RMSSD</text>
  <text x="{margin_left + 130}" y="{margin_top + top_h + 28}" font-size="14" fill="#f97316">Garmin HRV</text>
  <line x1="{margin_left}" y1="{bottom_y}" x2="{margin_left}" y2="{bottom_y + bottom_h}" stroke="#334155" />
  <line x1="{margin_left}" y1="{bottom_y + bottom_h}" x2="{width - margin_right}" y2="{bottom_y + bottom_h}" stroke="#334155" />
  <text x="22" y="{bottom_y + 18}" font-size="13" fill="#334155" transform="rotate(-90 22,{bottom_y + 18})">Polar mean HR bpm</text>
  {polyline(hr_line, "#0f766e", 3)}
  <text x="{margin_left}" y="{height - 12}" font-size="13" fill="#64748b">Polar epochs: {len(polar)}; Garmin HRV samples: {len(garmin)}; Polar quality: {html.escape(quality_text)}</text>
</svg>
"""


def main() -> None:
    args = parse_args()
    health = connect(args.health_db)
    try:
        polar = polar_points(health, args.date)
        sleep_start, sleep_end = sleep_window(health, args.date)
    finally:
        health.close()
    garmin = garmin_points(args.garmin_db, args.date)
    svg = render_svg(args.date, polar, garmin, sleep_start, sleep_end)
    Path(args.out).write_text(svg)
    print(args.out)


if __name__ == "__main__":
    main()
