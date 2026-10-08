package dev.zmeyka.avpnp

import android.content.Context
import android.util.Log

/**
 * Publishes avpnp's routing flag as a single global file in /data/local/tmp.
 *
 * The injector checks this file before touching anything:
 *   - present -> inject (hand the client an avpnp-owned TUN)
 *   - absent  -> the client runs its normal logic, untouched
 *
 * Two details make the file readable by ordinary apps:
 *   - mode 0644, and
 *   - `chcon u:object_r:system_file:s0`, because files created by root carry no app MLS category and
 *     an app's read would otherwise be denied by the MLS constraint. system_file is a trusted object
 *     type that apps may read.
 *
 * Per-client granularity stays with avpnp (the broker's isRoutingEnabled); this file is the global
 * fail-safe gate.
 */
object FlagPublisher {

    const val FLAG_PATH = "/data/local/tmp/avpnp.routing"
    private const val TAG = "avpnp"

    fun sync(context: Context, log: (String) -> Unit = {}) {
        val enabled = RoutingPrefs.isMasterEnabled(context)

        val commands = if (enabled) {
            listOf(
                "printf 1 > $FLAG_PATH",
                "chmod 644 $FLAG_PATH",
                "chcon u:object_r:system_file:s0 $FLAG_PATH 2>/dev/null",
            )
        } else {
            listOf("rm -f $FLAG_PATH")
        }

        val command = commands.joinToString("; ") + "; true"
        Log.i(TAG, "syncing flag: $command")
        log("su -c $command")
        log("rc=${runRoot(command)}")
    }

    private fun runRoot(command: String): Int {
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            process.inputStream.bufferedReader().forEachLine { Log.i(TAG, "flag: $it") }
            process.waitFor()
        } catch (t: Throwable) {
            Log.e(TAG, "flag sync failed", t)
            -1
        }
    }
}
