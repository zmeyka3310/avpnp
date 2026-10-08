package dev.zmeyka.avpnp

import android.content.Context
import android.util.Log

/**
 * Installs avpnp's kernel routing state from the current intent: [AppAssignments] (which app goes
 * where) compiled against [ClientRegistry] (which clients exist and their tables).
 *
 * Deliberately dumb: no validation, no rollback, no transactions — reapply is flush-and-add.
 *
 * An assignment compiles to `uidrange <app uid> lookup <client table>`, installed for v4 and v6.
 * The table's `default dev <tun>` route is created by the TUN helper.
 *
 * The three exits for now:
 *   - bypass -> no rule at all; the app follows netd's normal path
 *   - block  -> `ip rule ... blackhole`, an action, so it needs no table
 *   - a client -> the client's table
 *
 * avpnp's own VPN table gets `throw default`, so traffic netd steers into avpnp's tunnel (but which
 * is not assigned to a client) abandons that table and continues to netd's normal rules, i.e. out
 * the physical uplink. That is what makes avpnp a real full-tunnel VPN without becoming a blackhole.
 */
object Routing {

    private const val TAG = "avpnp"

    fun apply(context: Context, log: (String) -> Unit = {}) {
        val command = buildCommand(context)
        log("su -c $command")
        log("rc=${runRoot(command)}")
    }

    private fun buildCommand(context: Context): String {
        val commands = mutableListOf<String>()

        // Flush the whole band first, so rules for removed assignments cannot linger.
        commands += flushBand("-4")
        commands += flushBand("-6")

        // Let unassigned traffic escape avpnp's own tunnel.
        commands += ensureThrow("-4")
        commands += ensureThrow("-6")

        val clients = ClientRegistry.enabled(context).associateBy { it.packageName }
        var priority = AvpnpConfig.RULE_PRIORITY_BASE

        for ((app, destination) in AppAssignments.all(context)) {
            // avpnp itself is never routed: it must keep a reliable path to its clients and to root.
            if (app == context.packageName) continue
            // Bypass needs no rule: with no rule the app simply follows netd's normal path.
            if (destination == AppAssignments.BYPASS) continue
            // A client may be routed into another client (tunnel in tunnel), but never into itself:
            // its own server traffic would go straight back into the tunnel it feeds.
            if (destination == app) {
                Log.w(TAG, "ignoring self-assignment of $app")
                continue
            }

            val uid = runCatching {
                context.packageManager.getPackageUid(app, 0)
            }.getOrElse {
                Log.w(TAG, "cannot resolve $app: $it")
                -1
            }
            if (uid < 0) continue

            if (destination == AppAssignments.BLOCK) {
                // A rule action, so no table or interface is needed to drop the traffic.
                val p = priority++
                commands += "ip -4 rule add priority $p uidrange $uid-$uid blackhole 2>/dev/null"
                commands += "ip -6 rule add priority $p uidrange $uid-$uid blackhole 2>/dev/null"
                continue
            }

            val client = clients[destination] ?: continue
            val p = priority++
            commands += "ip -4 rule add priority $p uidrange $uid-$uid lookup ${client.table} 2>/dev/null"
            commands += "ip -6 rule add priority $p uidrange $uid-$uid lookup ${client.table} 2>/dev/null"
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
     * Replaces the default route of avpnp's own VPN table with `throw`, so traffic netd steers into
     * avpnp's tunnel — but which no assignment claims — abandons the table and continues to netd's
     * normal rules, i.e. out the physical uplink.
     *
     * The table id cannot be guessed: it is neither the interface name nor derived from the ifindex.
     * It is whatever netd's blanket VPN rule points at, so read it from that rule.
     */
    private fun ensureThrow(family: String): String {
        val find =
            "tbl=\$(ip $family rule show | awk '/^13000:/ {if (index(\$0,\"fwmark 0x0/0x20000\")>0 && index(\$0,\"uidrange 0-99999\")>0) {print \$NF; exit}}')"
        return "$find; [ -n \"\$tbl\" ] && ip $family route replace throw default table \"\$tbl\" 2>/dev/null"
    }

    /**
     * Flushes avpnp's rule band without touching any interface.
     *
     * Used when avpnp's own VPN goes away. Client TUNs belong to the clients, so they must survive:
     * deleting them here would kill every client's tunnel while the client kept running with a dead
     * fd. The manual Cleanup button is the one that removes interfaces.
     */
    fun flushOnly(context: Context, log: (String) -> Unit = {}) {
        val command = listOf(flushBand("-4"), flushBand("-6")).joinToString("; ") + "; true"
        log("su -c $command")
        log("rc=${runRoot(command)}")
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

    /**
     * Force-stops the given packages. A client only picks up the injector at process start, so this
     * is how you re-inject one: kill it, then open it again.
     */
    fun forceStop(packages: List<String>, log: (String) -> Unit = {}) {
        if (packages.isEmpty()) return
        val command = packages.joinToString("; ") { "am force-stop $it" } + "; true"
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
