package dev.zmeyka.avpnp

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Creates a client's TUN by running avpnp's root helper and receiving the resulting fd.
 *
 * The helper runs as root (via `su`), opens /dev/net/tun, configures the interface, and sends the fd
 * back over an abstract Unix socket using SCM_RIGHTS. It makes no policy decisions: every parameter
 * comes from the client's [ClientConfig].
 */
object TunFactory {

    private const val TAG = "avpnp"
    private const val ACCEPT_TIMEOUT_SECONDS = 20L

    fun create(context: Context, config: ClientConfig): ParcelFileDescriptor {
        val socketName = "avpnp_tun_${Process.myPid()}_${System.nanoTime()}"
        val server = LocalServerSocket(socketName)

        try {
            val helper = File(context.applicationInfo.nativeLibraryDir, "libavpnp_tun.so")
            check(helper.exists()) { "helper missing at ${helper.absolutePath}" }

            val command = listOf(
                helper.absolutePath,
                "--connect", socketName,
                "--if", config.tunName,
                "--mtu", config.mtu.toString(),
                "--addr", config.address,
                "--table", config.table.toString(),
            ).joinToString(" ")

            Log.i(TAG, "starting helper: su -c $command")
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()

            // Drain helper output so it cannot block on a full pipe.
            Thread {
                process.inputStream.bufferedReader().forEachLine { Log.i(TAG, "helper: $it") }
            }.start()

            val executor = Executors.newSingleThreadExecutor()
            val client: LocalSocket = try {
                executor.submit(Callable { server.accept() })
                    .get(ACCEPT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } finally {
                executor.shutdown()
            }

            // Reading is what actually receives the ancillary data.
            val byte = client.inputStream.read()
            val fds = client.ancillaryFileDescriptors
            Log.i(TAG, "helper message byte=$byte ancillaryFds=${fds?.size ?: 0}")

            if (fds.isNullOrEmpty()) {
                client.close()
                throw IllegalStateException("helper sent no file descriptor")
            }

            val pfd = ParcelFileDescriptor.dup(fds[0])
            client.close()
            Log.i(TAG, "TUN ready for ${config.packageName}: ${config.tunName} fd=${pfd.fd}")
            return pfd
        } finally {
            runCatching { server.close() }
        }
    }
}
