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
 * There is no compiled-in list of clients: the module is injected into whatever packages LSPosed
 * scopes it to, and asks avpnp at runtime whether the package is a registered client and what TUN it
 * should get. That is what makes the number of clients open-ended.
 *
 * Two paths, chosen by a global flag avpnp publishes at [FlagPublisher.FLAG_PATH]:
 *   - absent  -> the client runs its normal logic; nothing is substituted (fail-safe)
 *   - present -> inject: VpnService.prepare() answers null and Builder.establish() returns an
 *                avpnp-owned TUN fd
 */
class AvpnpModule : IXposedHookLoadPackage {

    private var broker: IAvpnpBroker? = null
    private var brokerLatch: CountDownLatch? = null
    private var brokerConnection: ServiceConnection? = null
    private var vpnServiceContext: Context? = null

    @Volatile
    private var flagSeen = false

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val packageName = lpparam.packageName
        if (packageName == "android" || packageName == "dev.zmeyka.avpnp") return

        log("injected into $packageName")
        prebindAtProcessStart(lpparam)
        captureVpnServiceContext(lpparam)
        hookPrepare(lpparam)
        hookEstablish(lpparam)
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
                // after attachBaseContext() has run.
                Handler(Looper.getMainLooper()).post { prebind(context) }
            }
        })
    }

    private fun hookPrepare(lpparam: XC_LoadPackage.LoadPackageParam) {
        val packageName = lpparam.packageName
        val vpnServiceClass = XposedHelpers.findClass("android.net.VpnService", lpparam.classLoader)
        XposedBridge.hookAllMethods(vpnServiceClass, "prepare", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (clientConfig(packageName) == null) {
                    log("[$packageName] not a registered client; prepare() passes through")
                    return
                }
                log("[$packageName] prepare() -> null")
                param.result = null
            }
        })
    }

    private fun hookEstablish(lpparam: XC_LoadPackage.LoadPackageParam) {
        val packageName = lpparam.packageName
        val builderClass = XposedHelpers.findClass("android.net.VpnService\$Builder", lpparam.classLoader)
        XposedBridge.hookAllMethods(builderClass, "establish", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val config = clientConfig(packageName)
                    if (config == null) {
                        log("[$packageName] not a registered client; establish() passes through")
                        return
                    }
                    val context = vpnServiceContext ?: run {
                        log("[$packageName] no Context yet; passing through")
                        return
                    }
                    val fd = acquireTun(config, context) ?: run {
                        log("[$packageName] no TUN available; passing through")
                        return
                    }
                    log("[$packageName] established with avpnp TUN fd=${fd.fd}")
                    param.result = fd
                } catch (t: Throwable) {
                    log("[$packageName] establish hijack failed: $t")
                }
            }
        })
    }

    /** Asks avpnp whether this package is a registered client, and for its tunnel parameters. */
    private fun clientConfig(packageName: String): ClientConfig? {
        if (!globalFlagPresent()) return null
        val service = broker ?: return null
        return try {
            val encoded = service.clientConfig(packageName)
            if (encoded.isEmpty()) null else ClientConfig.decode(packageName, encoded)
        } catch (t: Throwable) {
            log("clientConfig($packageName) failed: $t")
            null
        }
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
    private fun acquireTun(config: ClientConfig, context: Context): ParcelFileDescriptor? {
        broker?.let { return it.acquireTun(config.packageName) }

        prebind(context)
        // Keep well under the client's startForeground() deadline.
        val latch = brokerLatch ?: return null
        if (!latch.await(3, TimeUnit.SECONDS)) {
            log("broker not ready within 3s")
            return null
        }
        return broker?.acquireTun(config.packageName)
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
