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
 * The privileged surface avpnp exposes to hijacked clients.
 *
 * It is deliberately registry-driven rather than hardcoded: a client asks for its own tunnel
 * parameters, so clients can be added or removed at runtime. Callers are uid-checked against the
 * package they claim to be.
 *
 * avpnp must be installed with the force-queryable override for clients to be allowed to bind.
 */
class AvpnpBrokerService : Service() {

    override fun onBind(intent: Intent?): IBinder = binder

    private val binder = object : IAvpnpBroker.Stub() {

        override fun acquireTun(clientPackage: String): ParcelFileDescriptor? {
            return try {
                requireCaller(clientPackage)
                val config = ClientRegistry.get(this@AvpnpBrokerService, clientPackage) ?: return null
                Log.i(TAG, "acquireTun requested by $clientPackage")

                val pfd = TunFactory.create(this@AvpnpBrokerService, config)

                // Binder duplicates the fd into the reply. Once that has happened we must drop our
                // own copy, otherwise the interface outlives the client (and a later connection
                // fails with EBUSY on TUNSETIFF).
                Handler(Looper.getMainLooper()).postDelayed({
                    runCatching { pfd.close() }
                    Log.i(TAG, "released local copy of ${config.tunName}")
                }, RELEASE_DELAY_MS)

                pfd
            } catch (t: Throwable) {
                Log.e(TAG, "acquireTun failed for $clientPackage", t)
                null
            }
        }

        override fun applyRouting() {
            Log.i(TAG, "applyRouting requested")
            Routing.apply(this@AvpnpBrokerService) { Log.i(TAG, "routing: $it") }
        }

        override fun isRoutingEnabled(clientPackage: String): Boolean {
            return try {
                RoutingPrefs.isMasterEnabled(this@AvpnpBrokerService) &&
                    ClientRegistry.get(this@AvpnpBrokerService, clientPackage) != null
            } catch (t: Throwable) {
                Log.e(TAG, "isRoutingEnabled failed for $clientPackage", t)
                false
            }
        }

        override fun clientConfig(clientPackage: String): String {
            return try {
                requireCaller(clientPackage)
                ClientRegistry.get(this@AvpnpBrokerService, clientPackage)?.encode() ?: ""
            } catch (t: Throwable) {
                Log.e(TAG, "clientConfig failed for $clientPackage", t)
                ""
            }
        }
    }

    /** The caller must actually be the package it names. */
    private fun requireCaller(clientPackage: String) {
        val caller = Binder.getCallingUid()
        val owner = runCatching { packageManager.getPackageUid(clientPackage, 0) }.getOrDefault(-1)
        if (owner != caller) {
            throw SecurityException("uid $caller is not $clientPackage")
        }
    }

    private companion object {
        const val TAG = "avpnp"
        const val RELEASE_DELAY_MS = 3000L
    }
}
