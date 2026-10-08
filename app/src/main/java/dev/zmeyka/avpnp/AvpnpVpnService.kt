package dev.zmeyka.avpnp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log

/**
 * avpnp's own VpnService: it owns the single VPN slot for the profile so hijacked clients never
 * need to (and so Android cannot hand the slot to anyone else).
 *
 * Step 1 is deliberately a no-op: it establishes, routes nothing, and is scoped with an allowed-app
 * list containing only avpnp itself, so no other app's traffic is captured. Its only jobs are to
 * hold the slot and to make Android treat avpnp as the active VPN.
 */
class AvpnpVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()
        if (tun == null) {
            establishNoOpVpn()
        }
        // Clients check this flag on their next prepare()/establish().
        Thread { runCatching { FlagPublisher.sync(this) { Log.i(TAG, "flag: $it") } } }.start()
        return START_STICKY
    }

    private fun establishNoOpVpn() {
        if (prepare(this) != null) {
            Log.w(TAG, "not prepared for VPN; cannot establish")
            return
        }
        tun = try {
            Builder()
                .setSession("avpnp")
                .addAddress(TUN_ADDRESS, 32)
                // Keep it a per-app VPN that captures nothing that matters.
                .addAllowedApplication(packageName)
                .establish()
        } catch (t: Throwable) {
            Log.e(TAG, "establish failed", t)
            null
        }
        Log.i(TAG, "avpnp VPN established: ${tun != null} (fd=${tun?.fd})")
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
