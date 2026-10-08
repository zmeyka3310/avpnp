package dev.zmeyka.avpnp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import java.net.InetAddress

/**
 * avpnp's own VpnService.
 *
 * It owns the single VPN slot for the profile and now behaves as a real full-tunnel VPN: routes
 * 0.0.0.0/0 and ::/0, no app allowlist. Everything netd steers into it that is not assigned to a
 * client is sent back out by replacing avpnp's own table default with `throw` (see [Routing]), so
 * unassigned traffic follows netd's normal rules to the physical uplink instead of being blackholed.
 */
class AvpnpVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Refuse to establish while any app is unassigned: "unassigned" must never silently mean
        // "bypass". Only checks on first establish, so a running tunnel is never torn down by this.
        if (tun == null) {
            val unassigned = AppAssignments.unassigned(this)
            if (unassigned.isNotEmpty()) {
                Log.w(TAG, "refusing to start: ${unassigned.size} app(s) unassigned")
                stopSelf()
                return START_NOT_STICKY
            }
        }

        startForegroundNotification()
        if (tun == null) {
            establishVpn()
        }
        // Clients check this flag on their next prepare()/establish().
        Thread { runCatching { FlagPublisher.sync(this) { Log.i(TAG, "flag: $it") } } }.start()
        return START_STICKY
    }

    private fun establishVpn() {
        if (prepare(this) != null) {
            Log.w(TAG, "not prepared for VPN; cannot establish")
            return
        }

        tun = try {
            val builder = Builder()
                .setSession("avpnp")
                .addAddress(TUN_ADDRESS, 32)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)

            // A VPN network with no DNS servers resolves nothing, and every app is on this network.
            // Adopt the underlying network's servers; their packets escape via `throw`.
            underlyingDnsServers().forEach { address ->
                runCatching { builder.addDnsServer(address) }
                    .onFailure { Log.w(TAG, "cannot add DNS $address: $it") }
            }

            builder.establish()
        } catch (t: Throwable) {
            Log.e(TAG, "establish failed", t)
            null
        }
        Log.i(TAG, "avpnp VPN established: ${tun != null} (fd=${tun?.fd})")

        if (tun != null) {
            // Give netd a moment to create the VPN table, then compile the current intent.
            Thread {
                Thread.sleep(1000)
                runCatching { Routing.apply(this) { Log.i(TAG, "routing: $it") } }
            }.start()
        }
    }

    override fun onRevoke() {
        Log.i(TAG, "avpnp VPN revoked by the system")
        tun?.let { runCatching { it.close() } }
        tun = null
        cleanupRouting()
        super.onRevoke()
    }

    override fun onDestroy() {
        tun?.let { runCatching { it.close() } }
        tun = null
        cleanupRouting()
        super.onDestroy()
    }

    /**
     * Drops avpnp's kernel routing state. Without this, stopping avpnp leaves rules pointing at
     * TUNs that no longer exist, which silently blackholes the affected apps.
     */
    private fun cleanupRouting() {
        Thread {
            runCatching { Routing.cleanup(this) { Log.i(TAG, "cleanup: $it") } }
        }.start()
    }

    /**
     * DNS servers to advertise on the VPN network.
     *
     * The underlying network's resolvers are often public ones that the network blocks on plain
     * UDP:53 — the system resolver copes because it falls back to DoH, but an app doing its own DNS
     * does not. A gateway almost always answers plain DNS and is on the LAN, so put it first.
     */
    private fun underlyingDnsServers(): List<InetAddress> {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val network = manager.activeNetwork ?: return emptyList()
        val linkProperties = manager.getLinkProperties(network) ?: return emptyList()

        // Connected routes carry a wildcard "gateway" (0.0.0.0 / ::); those are not resolvers.
        val gateways = linkProperties.routes
            .mapNotNull { it.gateway }
            .filterNot { it.isAnyLocalAddress }
        val servers = (gateways + linkProperties.dnsServers).distinct()
        Log.i(TAG, "advertising DNS: ${servers.joinToString()}")
        return servers
    }

    private fun startForegroundNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "avpnp", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("avpnp")
            .setContentText("VPN slot held by avpnp")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private companion object {
        const val TAG = "avpnp"
        const val CHANNEL_ID = "avpnp"
        const val NOTIFICATION_ID = 1
        const val TUN_ADDRESS = "10.99.0.1"
    }
}
