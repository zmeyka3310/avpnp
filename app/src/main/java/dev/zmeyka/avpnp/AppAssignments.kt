package dev.zmeyka.avpnp

import android.content.Context

/**
 * Per-app routing intent: which exit each app's traffic takes.
 *
 * Exits for now: [BYPASS] (straight out), [BLOCK] (dropped), or a registered client's package.
 * An app with no entry is unassigned — and avpnp refuses to start while any app is unassigned, so
 * "unassigned" can never silently mean "bypass".
 */
object AppAssignments {

    const val BYPASS = "bypass"
    const val BLOCK = "block"

    private const val FILE = "avpnp"
    private const val PREFIX = "assign_"

    /** packageName -> exit value (one of the constants, or a client package). */
    fun all(context: Context): Map<String, String> =
        prefs(context).all.entries
            .filter { it.key.startsWith(PREFIX) && it.value is String }
            .associate { it.key.removePrefix(PREFIX) to (it.value as String) }

    fun destinationOf(context: Context, packageName: String): String? =
        prefs(context).getString(PREFIX + packageName, null)

    fun setDestination(context: Context, packageName: String, destination: String?) {
        prefs(context).edit().apply {
            if (destination == null) remove(PREFIX + packageName)
            else putString(PREFIX + packageName, destination)
        }.apply()
    }

    /**
     * Apps the operator has not yet assigned to any exit. Only avpnp itself is exempt — client apps
     * still need an exit, because a client can legitimately be chained into another client.
     */
    fun unassigned(context: Context): List<String> =
        InstalledApps.packages(context).filter {
            it != context.packageName && destinationOf(context, it) == null
        }

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
