package dev.zmeyka.avpnp

import android.content.Context

/**
 * avpnp's global switches.
 *
 * The master switch gates whether the injector does anything at all: it is what publishes (or
 * removes) the global flag the module checks. Per-client and per-app granularity live in
 * [ClientRegistry] and [AppAssignments].
 */
object RoutingPrefs {

    private const val FILE = "avpnp"
    private const val MASTER = "routing_master"

    fun isMasterEnabled(context: Context): Boolean =
        prefs(context).getBoolean(MASTER, true)

    fun setMaster(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(MASTER, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
