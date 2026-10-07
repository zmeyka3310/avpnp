package dev.zmeyka.avpnp

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Placeholder UI. Shows avpnp's routing intent, starts avpnp's own VPN (the slot holder), and lets
 * the operator reapply routing. The real graph editor replaces this later.
 */
class MainActivity : Activity() {

    private companion object {
        const val REQUEST_VPN = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val status = TextView(this).apply {
            setPadding(64, 64, 64, 32)
            textSize = 15f
            text = buildString {
                append("avpnp\n\n")
                if (AvpnpConfig.profiles.isEmpty()) {
                    append("No VPN client profiles configured.\n")
                } else {
                    AvpnpConfig.profiles.forEach { profile ->
                        append("${profile.label}  (${profile.packageName})\n")
                        append("  routed through it: ${profile.routedApps.joinToString(", ")}\n")
                        append("  tun: ${profile.tunName} ${profile.tunAddress} mtu ${profile.tunMtu}\n")
                        append("  table: ${profile.table}\n\n")
                    }
                }
                append("Everything else exits directly.")
            }
        }

        val startVpn = Button(this).apply {
            text = "Start avpnp VPN (hold slot)"
            setOnClickListener {
                val consent = VpnService.prepare(this@MainActivity)
                if (consent != null) {
                    startActivityForResult(consent, REQUEST_VPN)
                } else {
                    startVpnService()
                }
            }
        }

        val apply = Button(this).apply {
            text = "Apply routing"
            setOnClickListener {
                Toast.makeText(this@MainActivity, "Applying…", Toast.LENGTH_SHORT).show()
                Thread {
                    Routing.apply(this@MainActivity) { line -> android.util.Log.i("avpnp", line) }
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Routing applied (see logcat)", Toast.LENGTH_SHORT).show()
                    }
                }.start()
            }
        }

        // Diagnostic: run the whole helper path locally, with no IPC. Tells us whether the ksu
        // domain can hand a TUN fd back to an app process at all.
        val testTun = Button(this).apply {
            text = "Test TUN (helper, no IPC)"
            setOnClickListener {
                Toast.makeText(this@MainActivity, "Creating TUN…", Toast.LENGTH_SHORT).show()
                Thread {
                    val profile = AvpnpConfig.profiles.firstOrNull()
                    val result = if (profile == null) {
                        "no profile configured"
                    } else {
                        try {
                            val pfd = TunFactory.create(this@MainActivity, profile)
                            val msg = "TUN OK: ${profile.tunName} fd=${pfd.fd}"
                            pfd.close()
                            msg
                        } catch (t: Throwable) {
                            "TUN FAILED: $t"
                        }
                    }
                    android.util.Log.i("avpnp", "test tun -> $result")
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, result, Toast.LENGTH_LONG).show()
                    }
                }.start()
            }
        }

        val cleanup = Button(this).apply {
            text = "Cleanup (flush rules + TUNs)"
            setOnClickListener {
                stopService(Intent(this@MainActivity, AvpnpVpnService::class.java))
                Toast.makeText(this@MainActivity, "Cleaning up…", Toast.LENGTH_SHORT).show()
                Thread {
                    Routing.cleanup(this@MainActivity) { line -> android.util.Log.i("avpnp", line) }
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Cleaned up (see logcat)", Toast.LENGTH_LONG).show()
                    }
                }.start()
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(startVpn)
            addView(apply)
            addView(testTun)
            addView(cleanup)
        }
        setContentView(root)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN) {
            if (resultCode == RESULT_OK) {
                startVpnService()
            } else {
                Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startVpnService() {
        startService(Intent(this, AvpnpVpnService::class.java))
        Toast.makeText(this, "avpnp VPN starting", Toast.LENGTH_SHORT).show()
    }
}
