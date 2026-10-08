package dev.zmeyka.avpnp

import android.content.Context
import android.util.Log

/**
 * Installs avpnp's kernel routing state.
 *
 * Deliberately dumb: it turns [AvpnpConfig] into `ip rule` commands and runs them as root. No
 * validation beyond what the graph shows, no rollback, and no transactions — reapply is
 * delete-and-add.
 *
 * A link `X -> Y` compiles to `uidrange <uid_X> lookup <tbl_Y>`, installed for v4 and v6. The
 * table's `default dev <tun>` route is created by the TUN helper. Apps with no rule fall through to
 * netd's normal path, i.e. straight out.
 */
object Routing {

    private const val TAG = "avpnp"

    fun apply(context: Context, log: (String) -> Unit = {}) {
        val command = buildCommand(context) ?: run {
            log("nothing to apply")
            return
        }

        log("su -c $command")
        val result = runRoot(command)
        log("rc=$result")
    }

    private fun buildCommand(context: Context): String? {
        val commands = mutableListOf<String>()

        // Flush the whole band first. Deleting only the priorities we are about to add would leave
        // rules for links that have since been removed.
        commands += flushBand("-4")
        commands += flushBand("-6")

        var priority = AvpnpConfig.RULE_PRIORITY_BASE

        for (profile in AvpnpConfig.profiles) {
            for (app in profile.routedApps) {
                val uid = runCatching {
                    context.packageManager.getPackageUid(app, 0)
                }.getOrElse {
                    Log.w(TAG, "cannot resolve $app: $it")
                    -1
                }
                if (uid < 0) continue

                val p = priority++
                commands += "ip -4 rule add priority $p uidrange $uid-$uid lookup ${profile.table} 2>/dev/null"
                commands += "ip -6 rule add priority $p uidrange $uid-$uid lookup ${profile.table} 2>/dev/null"
            }
        }

        return commands.joinToString("; ") + "; true"
    }

    /** Deletes every rule avpnp owns, without touching anything outside its priority band. */
    private fun flushBand(family: String): String {
        // The awk program is single-quoted for the shell, so $1 must reach awk literally.
        val awk = "awk -F: '\$1+0>=${AvpnpConfig.RULE_PRIORITY_BASE} && \$1+0<=${AvpnpConfig.RULE_PRIORITY_END} {print \$1+0}'"
        return "ip $family rule show | $awk | while read p; do ip $family rule del priority \$p 2>/dev/null; done"
    }

    /**
     * Removes everything avpnp installed: every rule in its band, and its TUN interfaces. Needed
     * because disabling the module does not remove kernel state, and a rule pointing at a deleted
     * TUN silently blackholes that app's traffic.
     */
    fun cleanup(context: Context, log: (String) -> Unit = {}) {
        val deleteInterfaces =
            "ip -o link show | awk -F': ' '/avpnp/{print \$2}' | while read d; do ip link del \"\$d\" 2>/dev/null; done"
        val command = listOf(flushBand("-4"), flushBand("-6"), deleteInterfaces)
            .joinToString("; ") + "; true"

        log("su -c $command")
        log("rc=${runRoot(command)}")
    }

    private fun runRoot(command: String): Int {
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            process.inputStream.bufferedReader().forEachLine { Log.i(TAG, "routing: $it") }
            process.waitFor()
        } catch (t: Throwable) {
            Log.e(TAG, "routing failed", t)
            -1
        }
    }
}
