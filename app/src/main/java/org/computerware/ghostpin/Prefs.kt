package org.computerware.ghostpin

import android.content.Context
import androidx.core.content.edit

const val COFFEE_URL = "https://buymeacoffee.com/playzleeyt"

/** Which popup (if any) the UI should currently show. */
enum class AppDialog { None, Setup, Coffee }

/** Tiny SharedPreferences wrapper for first-run and donation-prompt bookkeeping. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("ghostpin_prefs", Context.MODE_PRIVATE)

    var launchCount: Int
        get() = sp.getInt("launch_count", 0)
        set(v) = sp.edit { putInt("launch_count", v) }

    /** True once the user has dismissed the setup guide at least once. */
    var setupSeen: Boolean
        get() = sp.getBoolean("setup_seen", false)
        set(v) = sp.edit { putBoolean("setup_seen", v) }

    /** Launch number on which the coffee prompt was last shown. */
    var lastCoffeeLaunch: Int
        get() = sp.getInt("last_coffee_launch", 0)
        set(v) = sp.edit { putInt("last_coffee_launch", v) }
}