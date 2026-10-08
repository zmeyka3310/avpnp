package dev.zmeyka.avpnp

import android.content.Context

/**
 * Whether the injector should hijack a client, or let it behave as an ordinary VPN app.
 *
 * The value lives in avpnp's own preferences and is served to the module over the broker, so the
 * decision stays in avpnp and the injector remains policy-free. A change takes effect the next time
 * the client calls prepare()/establish().
 *
 * Note: with routing disabled the client runs its own VpnService, which means avpnp must not be
 * holding the VPN slot at the same time — Android allows only one.
 */
object RoutingPrefs {

    private const val FILE = "avpnp"
    private const val MASTER = "routing_master"

    fun isMasterEnabled(context: Context): Boolean =
        prefs(context).getBoolean(MASTER, true)

    fun setMaster(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(MASTER, enabled).apply()
    }

    fun isClientEnabled(context: Context, packageName: String): Boolean {
        val default = AvpnpConfig.profileFor(packageName)?.routingEnabledByDefault ?: true
        return prefs(context).getBoolean("routing_$packageName", default)
    }

    fun setClient(context: Context, packageName: String, enabled: Boolean) {
        prefs(context).edit().putBoolean("routing_$packageName", enabled).apply()
    }

    /** Effective decision: the master switch AND the per-client switch. */
    fun isEnabled(context: Context, packageName: String): Boolean =
        isMasterEnabled(context) && isClientEnabled(context, packageName)

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
