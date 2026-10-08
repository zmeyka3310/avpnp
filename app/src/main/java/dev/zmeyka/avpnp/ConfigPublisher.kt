package dev.zmeyka.avpnp

import android.content.Context
import android.util.Base64
import android.util.Log

/**
 * Publishes avpnp's config to /data/local/tmp so the injector can read it without binder.
 *
 * Written as root with mode 0644 and relabelled u:object_r:system_file:s0. The label is load-bearing:
 * a file created by a root domain carries no app MLS category, so an app's read would otherwise be
 * denied by the MLS constraint.
 *
 * The payload is base64-encoded on its way through the shell, which keeps arbitrary config content
 * free of quoting hazards.
 */
object ConfigPublisher {

    private const val TAG = "avpnp"

    fun sync(context: Context, log: (String) -> Unit = {}) {
        val snapshot = AvpnpTmpConfig.Snapshot(
            routingEnabled = RoutingPrefs.isMasterEnabled(context),
            clients = ClientRegistry.enabled(context).associateBy { it.packageName },
        )

        val encoded = Base64.encodeToString(
            AvpnpTmpConfig.encode(snapshot).toByteArray(),
            Base64.NO_WRAP,
        )

        val command = listOf(
            "printf %s '$encoded' | base64 -d > ${AvpnpTmpConfig.PATH}",
            "chmod 644 ${AvpnpTmpConfig.PATH}",
            "chcon u:object_r:system_file:s0 ${AvpnpTmpConfig.PATH} 2>/dev/null",
            // The previous build used a bare flag file; drop it so it cannot be mistaken for config.
            "rm -f /data/local/tmp/avpnp.routing",
        ).joinToString("; ") + "; true"

        Log.i(TAG, "publishing config (${snapshot.clients.size} client(s), routing=${snapshot.routingEnabled})")
        log("su -c $command")
        log("rc=${runRoot(command)}")
    }

    private fun runRoot(command: String): Int {
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            process.inputStream.bufferedReader().forEachLine { Log.i(TAG, "config: $it") }
            process.waitFor()
        } catch (t: Throwable) {
            Log.e(TAG, "config publish failed", t)
            -1
        }
    }
}
