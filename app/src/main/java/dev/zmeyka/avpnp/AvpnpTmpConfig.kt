package dev.zmeyka.avpnp

import android.util.Log
import java.io.File

/**
 * The world-readable config avpnp publishes for the injector to read.
 *
 * This is the entire contract between avpnp and an injected client: if the file is absent, or it
 * contains no `client|` line for this package, the injector leaves the client completely alone.
 * Nothing about routing policy crosses binder.
 *
 * Format, one record per line:
 *   routing=<0|1>
 *   client|<package>|<tunName>|<address>|<mtu>|<table>
 */
object AvpnpTmpConfig {

    const val PATH = "/data/local/tmp/avpnp.conf"

    private const val TAG = "avpnp"
    private const val CLIENT_PREFIX = "client|"

    data class Snapshot(
        val routingEnabled: Boolean,
        val clients: Map<String, ClientConfig>,
    ) {
        /** This package's client config, or null if it should run its normal logic. */
        fun client(packageName: String): ClientConfig? =
            if (routingEnabled) clients[packageName] else null
    }

    fun read(): Snapshot {
        return try {
            val file = File(PATH)
            if (!file.exists()) return Snapshot(false, emptyMap())

            var enabled = false
            val clients = mutableMapOf<String, ClientConfig>()

            file.readLines().forEach { raw ->
                val line = raw.trim()
                when {
                    line.startsWith("routing=") ->
                        enabled = line.substringAfter('=').trim() == "1"

                    line.startsWith(CLIENT_PREFIX) -> {
                        val parts = line.split("|")
                        if (parts.size >= 6) {
                            val mtu = parts[4].toIntOrNull()
                            val table = parts[5].toIntOrNull()
                            if (mtu != null && table != null) {
                                val config = ClientConfig(parts[1], parts[2], parts[3], mtu, table)
                                clients[config.packageName] = config
                            }
                        }
                    }
                }
            }
            Snapshot(enabled, clients)
        } catch (t: Throwable) {
            Log.w(TAG, "cannot read $PATH: $t")
            Snapshot(false, emptyMap())
        }
    }

    fun encode(snapshot: Snapshot): String = buildString {
        appendLine("routing=" + if (snapshot.routingEnabled) "1" else "0")
        snapshot.clients.values.sortedBy { it.table }.forEach { client ->
            appendLine(
                CLIENT_PREFIX +
                    "${client.packageName}|${client.tunName}|${client.address}|" +
                    "${client.mtu}|${client.table}"
            )
        }
    }
}
