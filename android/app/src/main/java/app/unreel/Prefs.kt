package app.unreel

import android.content.Context
import android.content.SharedPreferences

/** Settings shared between the UI and the accessibility service. */
class Prefs(context: Context) {
    val raw: SharedPreferences =
        context.applicationContext.getSharedPreferences("unreel", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = raw.getBoolean(KEY_ENABLED, true)
        set(v) = raw.edit().putBoolean(KEY_ENABLED, v).apply()

    /** Username typed by the user. Wins over the detected one. */
    var manualUsername: String
        get() = raw.getString(KEY_MANUAL, "") ?: ""
        set(v) = raw.edit().putString(KEY_MANUAL, normalize(v)).apply()

    /** Username learned from the Instagram profile tab. */
    var detectedUsername: String
        get() = raw.getString(KEY_DETECTED, "") ?: ""
        set(v) = raw.edit().putString(KEY_DETECTED, normalize(v)).apply()

    var blockedCount: Int
        get() = raw.getInt(KEY_COUNT, 0)
        set(v) = raw.edit().putInt(KEY_COUNT, v).apply()

    var showToasts: Boolean
        get() = raw.getBoolean(KEY_TOASTS, true)
        set(v) = raw.edit().putBoolean(KEY_TOASTS, v).apply()

    val ownUsername: String?
        get() = manualUsername.takeIf { isValid(it) } ?: detectedUsername.takeIf { isValid(it) }

    companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_MANUAL = "manual_username"
        const val KEY_DETECTED = "detected_username"
        const val KEY_COUNT = "blocked_count"
        const val KEY_TOASTS = "show_toasts"

        private val USERNAME = Regex("^[A-Za-z0-9._]{1,30}$")

        fun normalize(s: String): String = s.trim().removePrefix("@").trim()
        fun isValid(s: String): Boolean = USERNAME.matches(s)
    }
}
