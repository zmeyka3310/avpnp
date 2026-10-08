package dev.zmeyka.avpnp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Generic VPN-client hijack.
 *
 * Hooks the two Android framework methods every cooperative VpnService client shares:
 *   - `VpnService.prepare(Context)`  -> null, so the client believes it already has permission
 *   - `VpnService.Builder.establish()` -> an avpnp-owned TUN fd, so the client never binds to the
 *     framework and can run alongside other clients
 *
 * Client selection, TUN parameters, and routing all come from avpnp ([AvpnpConfig]); the client's
 * own settings are ignored. Adding another client is a data change, not a code change.
 *
 * The fd is fetched from avpnp's broker over binder. avpnp is installed `--force-queryable` so the
 * client is allowed to bind to it.
 */
class AvpnpModule : IXposedHookLoadPackage {

    private var broker: IAvpnpBroker? = null
    private var brokerLatch: CountDownLatch? = null
    private var brokerConnection: ServiceConnection? = null
    private var vpnServiceContext: Context? = null
    private var appContext: Context? = null
    private var fakeTunPeer: ParcelFileDescriptor? = null
    private var clientProfile: AvpnpConfig.ClientProfile? = null

    @Volatile
    private var routingEnabled = true

    @Volatile
    private var flagSeen = false

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val profile = AvpnpConfig.profileFor(lpparam.packageName) ?: return
        clientProfile = profile
        log("hijack target: ${profile.label} (${profile.packageName}), routed=${profile.routedApps}")
        prebindAtProcessStart(lpparam)
        captureVpnServiceContext(lpparam)
        hookPrepare(lpparam, profile)
        hookEstablish(lpparam, profile)
    }

    /**
     * Binds the broker as early as possible in the client's process. Some clients construct their
     * VpnService and call establish() immediately, so binding at construction time is too late.
     */
    private fun prebindAtProcessStart(lpparam: XC_LoadPackage.LoadPackageParam) {
        val contextWrapper = XposedHelpers.findClass("android.content.ContextWrapper", lpparam.classLoader)
        XposedBridge.hookAllMethods(contextWrapper, "attachBaseContext", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val context = param.thisObject as? Context ?: return
                if (context !is android.app.Application) return
                appContext = context
                prebind(context)
            }
        })
    }

    /**
     * Captures the client's VpnService instance. VpnService is a Context, and grabbing it at
     * construction avoids reflecting into the Builder's hidden inner-class fields.
     */
    private fun captureVpnServiceContext(lpparam: XC_LoadPackage.LoadPackageParam) {
        val vpnServiceClass = XposedHelpers.findClass("android.net.VpnService", lpparam.classLoader)
        XposedBridge.hookAllConstructors(vpnServiceClass, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val context = param.thisObject as? Context ?: return
                vpnServiceContext = context
                log("captured VpnService instance as Context")
                // During construction the Context is not attached yet, so bind on the main looper,
                // after attachBaseContext() has run. This keeps establish() from blocking the
                // client's service thread while it races its startForeground() deadline.
                Handler(Looper.getMainLooper()).post { prebind(context) }
            }
        })
    }

    private fun hookPrepare(lpparam: XC_LoadPackage.LoadPackageParam, profile: AvpnpConfig.ClientProfile) {
        val vpnServiceClass = XposedHelpers.findClass("android.net.VpnService", lpparam.classLoader)
        XposedBridge.hookAllMethods(vpnServiceClass, "prepare", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!hijackEnabled(profile)) {
                    log("[${profile.label}] routing disabled; prepare() passes through")
                    return
                }
                log("[${profile.label}] prepare() -> null (permission appears granted)")
                param.result = null
            }
        })
    }

    private fun hookEstablish(lpparam: XC_LoadPackage.LoadPackageParam, profile: AvpnpConfig.ClientProfile) {
        val builderClass = XposedHelpers.findClass("android.net.VpnService\$Builder", lpparam.classLoader)
        XposedBridge.hookAllMethods(builderClass, "establish", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!hijackEnabled(profile)) {
                    log("[${profile.label}] routing disabled; establish() passes through")
                    return
                }
                try {
                    val context = vpnServiceContext
                    if (context == null) {
                        log("[${profile.label}] no Context available yet")
                        return
                    }
                    val fd = if (AvpnpConfig.USE_FAKE_TUN) fakeTun(profile) else acquireTun(profile, context)
                    if (fd == null) {
                        log("[${profile.label}] no TUN available; letting the client fail normally")
                        return
                    }
                    log("[${profile.label}] established with avpnp TUN fd=${fd.fd}")
                    param.result = fd
                } catch (t: Throwable) {
                    log("[${profile.label}] establish hijack failed: $t")
                }
            }
        })
    }

    /** Starts binding to avpnp's broker ahead of time, asynchronously. */
    private fun prebind(context: Context) {
        if (broker != null || brokerConnection != null) return

        val latch = CountDownLatch(1)
        brokerLatch = latch

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                broker = IAvpnpBroker.Stub.asInterface(service)
                latch.countDown()
                log("avpnp broker connected")
                clientProfile?.let { profile ->
                    runCatching { broker!!.isRoutingEnabled(profile.packageName) }
                        .onSuccess { log("[${profile.label}] broker routing = $it") }
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                broker = null
                brokerConnection = null
                brokerLatch = null
                log("avpnp broker disconnected")
            }
        }
        brokerConnection = connection

        val intent = Intent().setComponent(
            ComponentName("dev.zmeyka.avpnp", "dev.zmeyka.avpnp.AvpnpBrokerService")
        )
        val bound = try {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (t: Throwable) {
            log("prebind bindService threw: $t")
            false
        }
        log("prebind to avpnp broker -> $bound")
        if (!bound) {
            latch.countDown()
            // Let a later call retry. Without this, a failed bind (e.g. avpnp being reinstalled)
            // would poison the client process for its whole lifetime.
            brokerConnection = null
            brokerLatch = null
        }
    }

    /**
     * Asks avpnp's broker for this client's TUN. By the time establish() runs the pre-bind has
     * normally completed, so this should not block meaningfully.
     */
    /**
     * Two paths, chosen by the presence of a global flag avpnp publishes at [FlagPublisher.FLAG_PATH]:
     *
     *  - flag absent  -> the client runs its normal logic; we never substitute anything
     *  - flag present -> inject: hand the client an avpnp-owned TUN
     *
     * The absent case is a true fail-safe: an injected module with no published flag cannot break a
     * client. Per-client granularity comes from the broker when it is reachable.
     */
    private fun hijackEnabled(profile: AvpnpConfig.ClientProfile): Boolean {
        val flagged = globalFlagPresent()
        if (!flagged) {
            if (routingEnabled) log("[${profile.label}] no routing flag; falling back to normal logic")
            routingEnabled = false
            return false
        }

        val service = broker
        val enabled = if (service == null) {
            true
        } else {
            try {
                service.isRoutingEnabled(profile.packageName)
            } catch (t: Throwable) {
                log("isRoutingEnabled failed: $t")
                true
            }
        }
        if (enabled != routingEnabled) log("[${profile.label}] routing enabled = $enabled")
        routingEnabled = enabled
        return enabled
    }

    private fun globalFlagPresent(): Boolean = runCatching {
        val flag = File(FLAG_PATH)
        flag.exists() && flag.readText().trim() == "1"
    }.getOrDefault(false).also { present ->
        if (present != flagSeen) {
            flagSeen = present
            log("routing flag present = $present")
        }
    }

    private fun acquireTun(profile: AvpnpConfig.ClientProfile, context: Context): ParcelFileDescriptor? {
        broker?.let { return it.acquireTun(profile.packageName) }

        prebind(context)
        // Keep well under the client's startForeground() deadline.
        val latch = brokerLatch ?: return null
        if (!latch.await(3, TimeUnit.SECONDS)) {
            log("broker not ready within 3s")
            return null
        }
        return broker?.acquireTun(profile.packageName)
    }

    /** Milestone fallback: a synthesized socketpair fd, no IPC. Only used if configured. */
    private fun fakeTun(profile: AvpnpConfig.ClientProfile): ParcelFileDescriptor {
        val pair = ParcelFileDescriptor.createReliableSocketPair()
        fakeTunPeer?.let { runCatching { it.close() } }
        fakeTunPeer = pair[1]
        log("[${profile.label}] synthesized stand-in TUN (client=${pair[0].fd}, held=${pair[1].fd})")
        return pair[0]
    }

    private fun log(message: String) {
        val line = "avpnp: $message"
        Log.i(TAG, line)
        runCatching { XposedBridge.log(line) }
    }

    private companion object {
        const val TAG = "avpnp"
        /** Must match [FlagPublisher.FLAG_PATH]. */
        const val FLAG_PATH = "/data/local/tmp/avpnp.routing"
    }
}
