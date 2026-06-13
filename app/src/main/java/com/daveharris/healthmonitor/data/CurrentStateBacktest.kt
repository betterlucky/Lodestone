package com.daveharris.healthmonitor.data

import java.time.LocalDate

/**
 * Leave-one-out backtest harness for Lodestone model v1 — a repo-native port of
 * the analysis-script backtest (local/analysis/deepdive2.py / current_state_backtest.py).
 *
 * It judges the persistence + caution rule against baselines on the
 * safety-relevant metric — **deterioration-catch** (did we warn before a downturn?)
 * — alongside plain accuracy. This is the standing validation loop from
 * docs/lodestone-model-v1.md §Validation loop: re-runnable as data grows so the
 * model must keep earning its keep.
 *
 * Honest caveats baked into the doc and memory: tiny n, thresholds set in-sample
 * (leakage), hypothesis-generating only. The model surfaces caution *beside* the
 * forecast in the app; here, per the doc, an ELEVATED caution is scored as a
 * one-step downgrade ("the backtest downgrade was a scoring convenience").
 *
 * PURE: the caller supplies the day series (feature extraction lives elsewhere).
 */
object CurrentStateBacktest {

    /** One day's lived outcome plus the passive features the rule may use. */
    data class DayObservation(
        val date: String,
        val sf: TrafficLightStatus?,
        val mvMinutes: Int?,
        val hrvCv24h: Double?
    )

    enum class Rule { MAJORITY, PERSIST, PERSIST_EXERT, PERSIST_EXERT_CV }

    data class RuleResult(
        val rule: Rule,
        val total: Int,
        val correct: Int,
        val transitionDays: Int,
        val transitionCorrect: Int,
        val deteriorationDays: Int,
        val deteriorationWarned: Int
    ) {
        val accuracy: Double get() = if (total == 0) 0.0 else correct.toDouble() / total
    }

    data class BacktestReport(
        val mvThreshold: Int?,
        val cvThreshold: Double?,
        val eligibleDays: Int,
        val transitionDays: Int,
        val deteriorationDays: Int,
        val results: List<RuleResult>
    ) {
        fun render(): String = buildString {
            appendLine("Current-state model v1 — leave-one-out backtest")
            appendLine("  thresholds: hi_mv>=${mvThreshold ?: "n/a"}min  hi_cv>=${cvThreshold?.let { "%.2f".format(it) } ?: "n/a"} (top-third, in-sample)")
            appendLine("  eligible=$eligibleDays  transitions=$transitionDays  deteriorations=$deteriorationDays (tiny — hypothesis-generating only)")
            results.forEach { r ->
                appendLine(
                    "  ${r.rule.name.padEnd(18)} acc=${r.correct}/${r.total}=${"%.0f%%".format(r.accuracy * 100)}" +
                        "  transition-correct=${r.transitionCorrect}/${r.transitionDays}" +
                        "  deterioration-warned=${r.deteriorationWarned}/${r.deteriorationDays}"
                )
            }
        }
    }

    private fun ord(s: TrafficLightStatus): Int = when (s) {
        TrafficLightStatus.CRASH -> 0
        TrafficLightStatus.UNSTEADY -> 1
        TrafficLightStatus.OK -> 2
        TrafficLightStatus.GOOD -> 3
    }

    private fun clampOrd(i: Int): Int = i.coerceIn(0, 3)

    /** Top-tier (default 66th-pct) cut-point of available values, matching deepdive2. */
    private fun topTier(values: List<Double>, tier: Double): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        return sorted[(sorted.size * tier).toInt().coerceIn(0, sorted.size - 1)]
    }

    fun run(series: List<DayObservation>, tier: Double = 0.66): BacktestReport {
        val byDate = series.associateBy { it.date }
        fun sfOrd(date: String): Int? = byDate[date]?.sf?.let { ord(it) }
        fun mv(date: String): Int? = byDate[date]?.mvMinutes
        fun cv(date: String): Double? = byDate[date]?.hrvCv24h
        fun shift(date: String, days: Long): String = LocalDate.parse(date).plusDays(days).toString()

        val hiMv = topTier(series.mapNotNull { it.mvMinutes?.toDouble() }, tier)?.toInt()
        val hiCv = topTier(series.mapNotNull { it.hrvCv24h }, tier)

        val dates = series.map { it.date }.sorted()
        val eligible = dates.filter { sfOrd(it) != null && sfOrd(shift(it, -1)) != null }

        fun predict(rule: Rule, date: String): Int {
            val prev = sfOrd(shift(date, -1)) ?: return 1
            if (rule == Rule.MAJORITY) return 1
            if (rule == Rule.PERSIST) return prev
            var p = prev
            val mv1 = mv(shift(date, -1))
            val mv2 = mv(shift(date, -2))
            val c = cv(date)
            val exertHigh = hiMv != null && ((mv1 != null && mv1 >= hiMv) || (mv2 != null && mv2 >= hiMv))
            if (exertHigh) p = clampOrd(p - 1)
            if (rule == Rule.PERSIST_EXERT_CV && hiCv != null && c != null && c >= hiCv) p = clampOrd(p - 1)
            return p
        }

        val results = Rule.entries.map { rule ->
            var correct = 0; var total = 0
            var transitionDays = 0; var transitionCorrect = 0
            var deteriorationDays = 0; var deteriorationWarned = 0
            for (d in eligible) {
                val pred = predict(rule, d)
                val act = sfOrd(d)!!
                val prev = sfOrd(shift(d, -1))!!
                total++
                if (pred == act) correct++
                if (act != prev) { transitionDays++; if (pred == act) transitionCorrect++ }
                if (act < prev) { deteriorationDays++; if (pred < prev) deteriorationWarned++ }
            }
            RuleResult(rule, total, correct, transitionDays, transitionCorrect, deteriorationDays, deteriorationWarned)
        }

        val transitions = eligible.count { sfOrd(it) != sfOrd(shift(it, -1)) }
        val deteriorations = eligible.count { sfOrd(it)!! < sfOrd(shift(it, -1))!! }
        return BacktestReport(hiMv, hiCv, eligible.size, transitions, deteriorations, results)
    }
}
