package dev.zmeyka.avpnp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
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

    private val handler = Handler(Looper.getMainLooper())

    /**
     * netd rewrites the VPN table whenever the tunnel or its underlying network changes — which
     * silently discards the `throw` route and can leave every app pointing at a dead interface. So
     * watch the VPN network and recompile whenever it moves.
     */
    private val vpnCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = scheduleApply()
        override fun onLost(network: Network) = scheduleApply()
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
            scheduleApply()
    }

    private val applyRunnable = Runnable {
        Thread { runCatching { Routing.apply(this) { Log.i(TAG, "routing: $it") } } }.start()
    }

    /** Coalesces bursts of network callbacks into one recompile. */
    private fun scheduleApply() {
        handler.removeCallbacks(applyRunnable)
        handler.postDelayed(applyRunnable, APPLY_DEBOUNCE_MS)
    }

    private fun watchVpnNetwork() {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .build()
        runCatching { manager.registerNetworkCallback(request, vpnCallback) }
            .onFailure { Log.w(TAG, "cannot watch VPN network: $it") }
    }

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
        // Clients read this on their next prepare()/establish().
        Thread { runCatching { ConfigPublisher.sync(this) { Log.i(TAG, "config: $it") } } }.start()
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
            watchVpnNetwork()
            // Give netd a moment to create the VPN table, then compile the current intent.
            scheduleApply()
        }
    }

    override fun onRevoke() {
        Log.i(TAG, "avpnp VPN revoked by the system")
        tun?.let { runCatching { it.close() } }
        tun = null
        handler.removeCallbacks(applyRunnable)
        runCatching {
            getSystemService(ConnectivityManager::class.java)
                ?.unregisterNetworkCallback(vpnCallback)
        }
        cleanupRouting()
        super.onRevoke()
    }

    override fun onDestroy() {
        tun?.let { runCatching { it.close() } }
        tun = null
        handler.removeCallbacks(applyRunnable)
        runCatching {
            getSystemService(ConnectivityManager::class.java)
                ?.unregisterNetworkCallback(vpnCallback)
        }
        cleanupRouting()
        super.onDestroy()
    }

    /**
     * Drops avpnp's rules when its own VPN goes away, but deliberately leaves client TUNs alone:
     * those belong to the clients. The manual Cleanup button is what removes interfaces.
     */
    private fun cleanupRouting() {
        Thread {
            runCatching { Routing.flushOnly(this) { Log.i(TAG, "cleanup: $it") } }
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

        /** Long enough for netd to finish rewriting the table after it notifies us. */
        const val APPLY_DEBOUNCE_MS = 750L
    }
}
