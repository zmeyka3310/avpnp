package dev.zmeyka.avpnp

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log

/**
 * The privileged surface avpnp exposes to hijacked clients: it hands out per-client TUN fds and
 * installs avpnp's routing state.
 *
 * avpnp must be installed with `--force-queryable` for clients to be allowed to bind; callers are
 * additionally checked by uid against the configured client profiles, so a stray app cannot obtain
 * a TUN.
 */
class AvpnpBrokerService : Service() {

    override fun onBind(intent: Intent?): IBinder = binder

    private val binder = object : IAvpnpBroker.Stub() {

        override fun acquireTun(clientPackage: String): ParcelFileDescriptor? {
            return try {
                requireKnownCaller(clientPackage)
                val profile = AvpnpConfig.profileFor(clientPackage) ?: return null
                Log.i(TAG, "acquireTun requested by $clientPackage")
                val pfd = TunFactory.create(this@AvpnpBrokerService, profile)

                // Binder duplicates the fd into the reply. Once that has happened we must drop our
                // own copy, otherwise the interface outlives the client (and a later connection
                // fails with EBUSY on TUNSETIFF).
                Handler(Looper.getMainLooper()).postDelayed({
                    runCatching { pfd.close() }
                    Log.i(TAG, "released local copy of ${profile.tunName}")
                }, RELEASE_DELAY_MS)

                pfd
            } catch (t: Throwable) {
                Log.e(TAG, "acquireTun failed for $clientPackage", t)
                null
            }
        }

        override fun applyRouting() {
            requireKnownCaller(null)
            Log.i(TAG, "applyRouting requested")
            Routing.apply(this@AvpnpBrokerService) { Log.i(TAG, "routing: $it") }
        }
    }

    /**
     * Ensures the binder caller's uid belongs to a configured client package. When [clientPackage]
     * is non-null it must additionally match.
     */
    private fun requireKnownCaller(clientPackage: String?) {
        val callingUid = Binder.getCallingUid()
        val allowed = AvpnpConfig.profiles.any { profile ->
            val uid = runCatching {
                packageManager.getPackageUid(profile.packageName, 0)
            }.getOrDefault(-1)
            uid == callingUid && (clientPackage == null || clientPackage == profile.packageName)
        }
        if (!allowed) {
            throw SecurityException("uid $callingUid is not a configured avpnp client")
        }
    }

    private companion object {
        const val TAG = "avpnp"
        const val RELEASE_DELAY_MS = 3000L
    }
}
