package com.exact.stepcounter

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Single source of truth for step-baseline math, persisted values, and calorie
 * calculation. Used by MainActivity (foreground UI), StepForegroundService
 * (background sensor listener), and StepWidgetProvider (home screen widget),
 * so all three always show the exact same numbers.
 */
object StepRepository {

    const val PREFS = "step_prefs"
    const val KEY_BASELINE = "baseline_steps"
    const val KEY_BASELINE_DATE = "baseline_date"
    const val KEY_WEIGHT = "weight_kg"
    const val KEY_HEIGHT = "height_cm"
    const val KEY_ONBOARDED = "onboarded"
    const val KEY_LAST_STEPS = "last_steps_today"
    const val KEY_LAST_CALORIES = "last_calories_today"
    const val KEY_LAST_CUMULATIVE = "last_cumulative_since_boot"
    const val KEY_LAST_EVENT_TIME = "last_event_time_ms"
    const val KEY_ACCEPTED_CUMULATIVE = "accepted_cumulative_since_boot"

    // --- Walk session (Start/Stop) state - independent of the always-on daily/
    // widget tracking above, so adding this cannot break the widget feature. ---
    const val KEY_SESSION_ACTIVE = "session_active"
    const val KEY_SESSION_BASELINE_CUMULATIVE = "session_baseline_cumulative"
    const val KEY_SESSION_START_TIME = "session_start_time"
    const val KEY_SESSION_LIVE_STEPS = "session_live_steps"
    const val KEY_SESSION_LIVE_CALORIES = "session_live_calories"

    // Last completed session, so a re-opened summary can be shown if needed.
    const val KEY_LAST_SESSION_STEPS = "last_session_steps"
    const val KEY_LAST_SESSION_CALORIES = "last_session_calories"
    const val KEY_LAST_SESSION_DURATION_MS = "last_session_duration_ms"
    const val KEY_LAST_SESSION_DISTANCE_KM = "last_session_distance_km"

    // Persisted history of every completed session, newest first.
    const val KEY_SESSION_HISTORY = "session_history"
    private const val HISTORY_ENTRY_DELIMITER = ";;"
    private const val HISTORY_FIELD_DELIMITER = "|"
    private const val MAX_HISTORY_ENTRIES = 200

    const val ACTION_SESSION_UPDATED = "com.exact.stepcounter.SESSION_UPDATED"
    const val EXTRA_SESSION_STEPS = "extra_session_steps"
    const val EXTRA_SESSION_CALORIES = "extra_session_calories"

    const val ACTION_STEPS_UPDATED = "com.exact.stepcounter.STEPS_UPDATED"
    const val EXTRA_STEPS = "extra_steps"
    const val EXTRA_CALORIES = "extra_calories"

    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Feeds a fresh cumulative-since-boot reading from the hardware sensor through
     * the daily baseline logic, persists it, and returns steps walked today.
     * Re-anchors the baseline on first run, on reboot (counter dropped below the
     * stored baseline), or when the calendar day has changed.
     */
    fun computeStepsToday(context: Context, cumulativeSinceBoot: Int): Int {
        val p = prefs(context)
        val today = dayFormat.format(Date())
        val savedDate = p.getString(KEY_BASELINE_DATE, null)
        var baseline = p.getInt(KEY_BASELINE, -1)

        val needsNewBaseline = baseline < 0 || cumulativeSinceBoot < baseline || savedDate != today
        if (needsNewBaseline) {
            baseline = cumulativeSinceBoot
            p.edit()
                .putInt(KEY_BASELINE, baseline)
                .putString(KEY_BASELINE_DATE, today)
                .apply()
        }

        val stepsToday = (cumulativeSinceBoot - baseline).coerceAtLeast(0)
        p.edit().putInt(KEY_LAST_STEPS, stepsToday).apply()
        return stepsToday
    }

    /**
     * MET-based estimate: calories = MET(3.5, moderate walk) x weight(kg) x time(hr),
     * where time = distance / assumed walking speed, and distance comes from steps x
     * a height-derived stride length. An estimate, not a lab measurement.
     */
    fun calculateCalories(context: Context, steps: Int): Float {
        val p = prefs(context)
        val weight = p.getFloat(KEY_WEIGHT, 0f)
        val height = p.getFloat(KEY_HEIGHT, 0f)
        if (weight <= 0 || height <= 0) return -1f

        val strideMeters = height * 0.413f / 100f
        val distanceKm = steps * strideMeters / 1000f
        val met = 3.5f
        val assumedSpeedKmh = 5f
        val calories = met * weight * (distanceKm / assumedSpeedKmh)
        p.edit().putFloat(KEY_LAST_CALORIES, calories).apply()
        return calories
    }

    fun hasProfile(context: Context): Boolean {
        val p = prefs(context)
        return p.getFloat(KEY_WEIGHT, 0f) > 0 && p.getFloat(KEY_HEIGHT, 0f) > 0
    }

    fun isOnboarded(context: Context): Boolean = prefs(context).getBoolean(KEY_ONBOARDED, false)

    fun setProfile(context: Context, weightKg: Float, heightCm: Float) {
        prefs(context).edit()
            .putFloat(KEY_WEIGHT, weightKg)
            .putFloat(KEY_HEIGHT, heightCm)
            .putBoolean(KEY_ONBOARDED, true)
            .apply()
    }

    fun getWeight(context: Context): Float = prefs(context).getFloat(KEY_WEIGHT, 0f)
    fun getHeight(context: Context): Float = prefs(context).getFloat(KEY_HEIGHT, 0f)

    /** Last values written by the service/activity - what the widget reads. */
    fun getLastSteps(context: Context): Int = prefs(context).getInt(KEY_LAST_STEPS, 0)
    fun getLastCalories(context: Context): Float = prefs(context).getFloat(KEY_LAST_CALORIES, -1f)

    fun formatCalories(calories: Float): String =
        if (calories < 0) "-- kcal" else String.format(Locale.US, "%.1f kcal", calories)

    fun formatDuration(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    private val dateTimeFormat = SimpleDateFormat("MMM d, yyyy \u2022 h:mm a", Locale.US)

    fun formatDateTime(timeMs: Long): String = dateTimeFormat.format(Date(timeMs))

    // ---------------- Raw sensor value (for session baselining) ----------------

    fun setLastCumulative(context: Context, cumulative: Int) {
        prefs(context).edit().putInt(KEY_LAST_CUMULATIVE, cumulative).apply()
    }

    fun getLastCumulative(context: Context): Int = prefs(context).getInt(KEY_LAST_CUMULATIVE, -1)

    /**
     * Filters out implausible step bursts - e.g. shaking the phone, dropping it on a
     * table, or it rattling in a bag - before they ever reach the daily/session
     * counters. A human physically cannot exceed roughly 5 steps per second even
     * sprinting, so if the hardware sensor reports a jump that would require a
     * faster cadence than that, we treat it as motion noise rather than real steps:
     * the excess is subtracted back out so it never gets counted, now or later.
     *
     * Returns the "cleaned" cumulative value to feed into computeStepsToday /
     * updateSessionOnSensorEvent instead of the sensor's raw reading.
     */
    fun filterImplausibleSteps(context: Context, rawCumulative: Int): Int {
        val p = prefs(context)
        val now = System.currentTimeMillis()
        val lastTime = p.getLong(KEY_LAST_EVENT_TIME, 0L)
        val lastAccepted = p.getInt(KEY_ACCEPTED_CUMULATIVE, rawCumulative)

        if (lastTime == 0L) {
            // First reading ever - nothing to compare against, accept as-is.
            p.edit()
                .putLong(KEY_LAST_EVENT_TIME, now)
                .putInt(KEY_ACCEPTED_CUMULATIVE, rawCumulative)
                .putInt(KEY_RAW_MARKER, rawCumulative)
                .apply()
            return rawCumulative
        }

        val elapsedSec = (now - lastTime) / 1000.0
        val prevRaw = p.getInt(KEY_RAW_MARKER, rawCumulative)

        if (rawCumulative < prevRaw) {
            // Raw sensor value dropped - almost certainly a device reboot (the
            // hardware counter resets to 0). Don't apply noise filtering here;
            // pass the raw value straight through so the existing reboot-detection
            // in computeStepsToday (which compares against the daily baseline)
            // still works correctly.
            p.edit()
                .putLong(KEY_LAST_EVENT_TIME, now)
                .putInt(KEY_RAW_MARKER, rawCumulative)
                .putInt(KEY_ACCEPTED_CUMULATIVE, rawCumulative)
                .apply()
            return rawCumulative
        }

        val delta = (rawCumulative - prevRaw).coerceAtLeast(0)

        val maxPlausibleSteps = (elapsedSec * MAX_STEPS_PER_SECOND).toInt().coerceAtLeast(1)
        val acceptedDelta = if (delta > maxPlausibleSteps && elapsedSec < 2.0) {
            // A burst faster than humanly possible in a very short window - likely
            // shaking/handling noise. Only cap it for short windows; if elapsed time
            // is longer (e.g. the app was closed for a while), a big delta is normal.
            maxPlausibleSteps
        } else {
            delta
        }

        val cleanedCumulative = lastAccepted + acceptedDelta

        p.edit()
            .putLong(KEY_LAST_EVENT_TIME, now)
            .putInt(KEY_RAW_MARKER, rawCumulative)
            .putInt(KEY_ACCEPTED_CUMULATIVE, cleanedCumulative)
            .apply()

        return cleanedCumulative
    }

    private const val KEY_RAW_MARKER = "raw_cumulative_marker"
    private const val MAX_STEPS_PER_SECOND = 5.0

    // ---------------- Walk session ----------------

    data class SessionSummary(
        val steps: Int,
        val calories: Float,
        val durationMs: Long,
        val distanceKm: Float
    )

    /** A completed session as saved in the history collection. */
    data class SavedSession(
        val startTimeMs: Long,
        val steps: Int,
        val calories: Float,
        val durationMs: Long,
        val distanceKm: Float
    )

    fun isSessionActive(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SESSION_ACTIVE, false)

    fun getSessionStartTime(context: Context): Long =
        prefs(context).getLong(KEY_SESSION_START_TIME, 0L)

    /** Returns false (and starts nothing) if there is no recent sensor reading yet. */
    fun startSession(context: Context): Boolean {
        val cumulative = getLastCumulative(context)
        if (cumulative < 0) return false
        prefs(context).edit()
            .putBoolean(KEY_SESSION_ACTIVE, true)
            .putInt(KEY_SESSION_BASELINE_CUMULATIVE, cumulative)
            .putLong(KEY_SESSION_START_TIME, System.currentTimeMillis())
            .putInt(KEY_SESSION_LIVE_STEPS, 0)
            .putFloat(KEY_SESSION_LIVE_CALORIES, -1f)
            .apply()
        return true
    }

    /**
     * Called from the service on every sensor event while a session is active, so
     * the session's live step/calorie counters stay current.
     */
    fun updateSessionOnSensorEvent(context: Context, cumulativeSinceBoot: Int): SessionSummary? {
        if (!isSessionActive(context)) return null
        val p = prefs(context)
        val baseline = p.getInt(KEY_SESSION_BASELINE_CUMULATIVE, cumulativeSinceBoot)
        val steps = (cumulativeSinceBoot - baseline).coerceAtLeast(0)
        val elapsedMs = System.currentTimeMillis() - getSessionStartTime(context)
        val calories = calculateSessionCalories(context, steps, elapsedMs)
        p.edit()
            .putInt(KEY_SESSION_LIVE_STEPS, steps)
            .putFloat(KEY_SESSION_LIVE_CALORIES, calories)
            .apply()
        val duration = elapsedMs
        return SessionSummary(steps, calories, duration, distanceKm(context, steps))
    }

    fun getSessionLiveSteps(context: Context): Int = prefs(context).getInt(KEY_SESSION_LIVE_STEPS, 0)
    fun getSessionLiveCalories(context: Context): Float =
        prefs(context).getFloat(KEY_SESSION_LIVE_CALORIES, -1f)

    /**
     * Ends the session, persists the final summary, and returns it. Recomputes
     * calories one last time using the *actual* elapsed duration for the best
     * possible accuracy (rather than the slightly-stale value from the last
     * sensor event).
     */
    fun stopSession(context: Context): SessionSummary {
        val p = prefs(context)
        val steps = getSessionLiveSteps(context)
        val startTime = getSessionStartTime(context)
        val duration = (System.currentTimeMillis() - startTime).coerceAtLeast(0)
        val calories = calculateSessionCalories(context, steps, duration)
        val distance = distanceKm(context, steps)

        p.edit()
            .putBoolean(KEY_SESSION_ACTIVE, false)
            .putInt(KEY_LAST_SESSION_STEPS, steps)
            .putFloat(KEY_LAST_SESSION_CALORIES, calories)
            .putLong(KEY_LAST_SESSION_DURATION_MS, duration)
            .putFloat(KEY_LAST_SESSION_DISTANCE_KM, distance)
            .apply()

        appendToHistory(context, SavedSession(startTime, steps, calories, duration, distance))

        return SessionSummary(steps, calories, duration, distance)
    }

    private fun appendToHistory(context: Context, session: SavedSession) {
        val p = prefs(context)
        val existing = p.getString(KEY_SESSION_HISTORY, "") ?: ""
        val entry = listOf(
            session.startTimeMs, session.steps, session.calories, session.durationMs, session.distanceKm
        ).joinToString(HISTORY_FIELD_DELIMITER)

        val updated = if (existing.isBlank()) entry else "$entry$HISTORY_ENTRY_DELIMITER$existing"
        val entries = updated.split(HISTORY_ENTRY_DELIMITER).take(MAX_HISTORY_ENTRIES)
        p.edit().putString(KEY_SESSION_HISTORY, entries.joinToString(HISTORY_ENTRY_DELIMITER)).apply()
    }

    /** All saved sessions, newest first. */
    fun getSessionHistory(context: Context): List<SavedSession> {
        val raw = prefs(context).getString(KEY_SESSION_HISTORY, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split(HISTORY_ENTRY_DELIMITER).mapNotNull { entry ->
            val parts = entry.split(HISTORY_FIELD_DELIMITER)
            if (parts.size != 5) return@mapNotNull null
            try {
                SavedSession(
                    startTimeMs = parts[0].toLong(),
                    steps = parts[1].toInt(),
                    calories = parts[2].toFloat(),
                    durationMs = parts[3].toLong(),
                    distanceKm = parts[4].toFloat()
                )
            } catch (e: NumberFormatException) {
                null
            }
        }
    }

    fun clearSessionHistory(context: Context) {
        prefs(context).edit().remove(KEY_SESSION_HISTORY).apply()
    }

    private fun distanceKm(context: Context, steps: Int): Float {
        val height = getHeight(context)
        if (height <= 0) return -1f
        val strideMeters = height * 0.413f / 100f
        return steps * strideMeters / 1000f
    }

    /**
     * Session calories use the *actually measured* pace (real distance walked over
     * real elapsed time) rather than an assumed speed, and look up a MET value that
     * scales with that pace - a slow stroll and a brisk walk burn different amounts
     * of energy for the same step count, and this now reflects that. Falls back to
     * a moderate-walk assumption only when the session is too short to measure a
     * reliable pace from (avoids wild numbers from a 2-second, 3-step sample).
     */
    private fun calculateSessionCalories(context: Context, steps: Int, durationMs: Long): Float {
        val weight = getWeight(context)
        val height = getHeight(context)
        if (weight <= 0 || height <= 0) return -1f

        val strideMeters = height * 0.413f / 100f
        val distanceKmVal = steps * strideMeters / 1000f
        val elapsedHours = durationMs / 3_600_000f

        val met = if (elapsedHours > (8f / 3600f)) { // at least ~8 seconds of data
            val speedKmh = distanceKmVal / elapsedHours
            metForSpeed(speedKmh)
        } else {
            3.5f // not enough data yet to measure pace - use moderate-walk default
        }

        return met * weight * elapsedHours
    }

    /**
     * Standard compendium-of-physical-activities-style MET lookup for walking,
     * scaled by measured speed - slow stroll burns noticeably less per hour than
     * a brisk walk, which a single fixed MET value can't capture.
     */
    private fun metForSpeed(speedKmh: Float): Float = when {
        speedKmh < 2.0f -> 2.0f
        speedKmh < 4.0f -> 2.8f
        speedKmh < 5.5f -> 3.5f
        speedKmh < 6.5f -> 4.3f
        speedKmh < 8.0f -> 5.0f
        else -> 6.0f // brisk / light jog pace
    }
}
