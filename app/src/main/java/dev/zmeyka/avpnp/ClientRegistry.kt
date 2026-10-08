package dev.zmeyka.avpnp

import android.content.Context
import android.content.Intent

/**
 * The set of VPN clients avpnp is willing to hijack, and their tunnel parameters.
 *
 * Open-ended by design: any app that declares a VpnService is a candidate, and enabling one assigns
 * it a fresh table, address, and interface name. Nothing here is compiled in, so the number of
 * clients is unbounded.
 *
 * The module never sees this list directly; it asks the broker for its own entry, which is what lets
 * a client be added or removed without rebuilding the module.
 */
object ClientRegistry {

    private const val FILE = "avpnp"
    private const val PREFIX = "client_"
    private const val FIRST_TABLE = 2001

    fun get(context: Context, packageName: String): ClientConfig? {
        val encoded = prefs(context).getString(PREFIX + packageName, null) ?: return null
        return ClientConfig.decode(packageName, encoded)
    }

    fun enabled(context: Context): List<ClientConfig> =
        prefs(context).all.entries
            .filter { it.key.startsWith(PREFIX) && it.value is String }
            .mapNotNull { (key, value) ->
                ClientConfig.decode(key.removePrefix(PREFIX), value as String)
            }
            .sortedBy { it.table }

    fun enable(context: Context, packageName: String) {
        if (get(context, packageName) != null) return

        val used = enabled(context).map { it.table }.toSet()
        var table = FIRST_TABLE
        while (table in used) table++
        val index = table - (FIRST_TABLE - 1)

        val config = ClientConfig(
            packageName = packageName,
            // %d lets the kernel pick a unique instance, so reconnects cannot collide.
            tunName = "avpnp_${index}%d",
            address = "10.77.$index.1/24",
            mtu = 1500,
            table = table,
        )
        prefs(context).edit().putString(PREFIX + packageName, config.encode()).apply()
    }

    fun disable(context: Context, packageName: String) {
        prefs(context).edit().remove(PREFIX + packageName).apply()
    }

    /** Apps that declare a VpnService: the candidates for hijacking. */
    fun candidates(context: Context): List<Pair<String, String>> =
        context.packageManager
            .queryIntentServices(Intent("android.net.VpnService"), 0)
            .mapNotNull { it.serviceInfo?.packageName }
            .filter { it != context.packageName && it != "android" }
            .distinct()
            .map { packageName -> packageName to labelOf(context, packageName) }
            .sortedBy { it.second.lowercase() }

    private fun labelOf(context: Context, packageName: String): String = runCatching {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(packageName)

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
