package dev.zmeyka.avpnp

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

/**
 * The control plane, in its first flat form:
 *   - enable the VPN clients avpnp should hijack (discovered at runtime; open-ended)
 *   - group apps into folders, so a folder's exit can be set for all members at once
 *   - assign every app an exit: Bypass, Block, or a VPN client
 *
 * avpnp refuses to start until every app has an exit, so nothing is ever implicitly direct.
 */
class MainActivity : Activity() {

    private companion object {
        const val REQUEST_VPN = 1
    }

    private lateinit var clientsContainer: LinearLayout
    private lateinit var foldersContainer: LinearLayout
    private lateinit var appList: ListView
    private var appEntries: List<InstalledApps.Entry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        clientsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        foldersContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        appList = ListView(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                setPadding(48, 48, 48, 16)
                textSize = 14f
                text = "avpnp"
            })
            addView(CheckBox(this@MainActivity).apply {
                text = "Routing enabled (master)"
                isChecked = RoutingPrefs.isMasterEnabled(this@MainActivity)
                setOnCheckedChangeListener { _, checked ->
                    RoutingPrefs.setMaster(this@MainActivity, checked)
                    publishFlags()
                }
            })
            addView(sectionHeader("VPN clients (hijacked):"))
            addView(clientsContainer)
            addView(sectionHeader("Folders:"))
            addView(foldersContainer)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(startVpnButton())
                addView(applyButton())
                addView(cleanupButton())
                addView(reinjectButton())
            })
            addView(sectionHeader("Apps:"))
            addView(appList, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)

        buildClientList()
        buildFolderList()
        buildAppList()
    }

    private fun sectionHeader(text: String) = TextView(this).apply {
        setPadding(48, 24, 48, 8)
        textSize = 14f
        this.text = text
    }

    /** Unassigned, Bypass, Block, then one entry per enabled client. */
    private fun exitOptions(leading: AppListAdapter.Destination? = null): List<AppListAdapter.Destination> {
        val clients = ClientRegistry.enabled(this)
        val labels = ClientRegistry.candidates(this).toMap()
        return buildList {
            leading?.let { add(it) }
            add(AppListAdapter.Destination(null, "Unassigned"))
            add(AppListAdapter.Destination(AppAssignments.BYPASS, "Bypass (direct)"))
            add(AppListAdapter.Destination(AppAssignments.BLOCK, "Block"))
            clients.forEach { client ->
                add(
                    AppListAdapter.Destination(
                        client.packageName,
                        labels[client.packageName] ?: client.packageName,
                    )
                )
            }
        }
    }

    private fun buildClientList() {
        clientsContainer.removeAllViews()
        val candidates = ClientRegistry.candidates(this)

        if (candidates.isEmpty()) {
            clientsContainer.addView(TextView(this).apply { text = "  (none found)" })
            return
        }

        candidates.forEach { (packageName, label) ->
            clientsContainer.addView(CheckBox(this).apply {
                text = "  $label"
                isChecked = ClientRegistry.get(this@MainActivity, packageName) != null
                setOnCheckedChangeListener { _, checked ->
                    if (checked) ClientRegistry.enable(this@MainActivity, packageName)
                    else ClientRegistry.disable(this@MainActivity, packageName)
                    // The exit list changed, so both sections need rebuilding.
                    buildClientList()
                    buildFolderList()
                    buildAppList()
                }
            })
        }
    }

    private fun buildFolderList() {
        foldersContainer.removeAllViews()

        foldersContainer.addView(Button(this).apply {
            text = "  New folder"
            setOnClickListener { promptNewFolder() }
        })

        val folders = AppFolders.all(this)
        if (folders.isEmpty()) {
            foldersContainer.addView(TextView(this).apply { text = "  (no folders)" })
            return
        }

        // A leading no-op entry so the initial selection does not fire an assignment.
        val options = exitOptions(AppListAdapter.Destination(null, "Set all members to…"))

        folders.forEach { folder ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(48, 8, 48, 8)
            }
            row.addView(TextView(this).apply {
                text = "${folder.name} (${folder.members.size})"
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(Spinner(this).apply {
                adapter = ArrayAdapter(
                    this@MainActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    options.map { it.label },
                )
                setSelection(0, false)
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, pos: Int, id: Long) {
                        if (pos == 0) return
                        // A null value means "unassigned", which is a legitimate folder action.
                        val value = options[pos].value
                        folder.members.forEach { member ->
                            AppAssignments.setDestination(this@MainActivity, member, value)
                        }
                        buildAppList()
                        applyRouting()
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                }
            })
            row.addView(Button(this).apply {
                text = "Members"
                setOnClickListener { promptMembers(folder) }
            })
            foldersContainer.addView(row)
        }
    }

    private fun promptNewFolder() {
        val input = EditText(this)
        AlertDialog.Builder(this)
            .setTitle("New folder")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().ifBlank { "Folder" }
                AppFolders.create(this, name)
                buildFolderList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptMembers(folder: AppFolders.Folder) {
        if (appEntries.isEmpty()) {
            Toast.makeText(this, "App list still loading", Toast.LENGTH_SHORT).show()
            return
        }

        val entries = appEntries
        val labels = entries.map { it.label }.toTypedArray()
        val checked = BooleanArray(entries.size) { entries[it].packageName in folder.members }

        AlertDialog.Builder(this)
            .setTitle("${folder.name} members")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Save") { _, _ ->
                val members = entries.filterIndexed { i, _ -> checked[i] }.map { it.packageName }
                AppFolders.setMembers(this, folder.id, members)
                buildFolderList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun buildAppList() {
        val options = exitOptions()

        Thread {
            // Only avpnp itself is exempt. Client apps are routable: a client can be chained into
            // another client (tunnel in tunnel).
            val entries = InstalledApps.entries(this).filter { it.packageName != packageName }
            appEntries = entries
            runOnUiThread {
                appList.adapter = AppListAdapter(this, entries, options) { applyRouting() }
            }
        }.start()
    }

    private fun startVpnButton() = Button(this).apply {
        text = "Start avpnp VPN"
        setOnClickListener {
            val unassigned = AppAssignments.unassigned(this@MainActivity)
            if (unassigned.isNotEmpty()) {
                Toast.makeText(
                    this@MainActivity,
                    "${unassigned.size} app(s) unassigned — assign every app first",
                    Toast.LENGTH_LONG,
                ).show()
                return@setOnClickListener
            }
            val consent = VpnService.prepare(this@MainActivity)
            if (consent != null) {
                startActivityForResult(consent, REQUEST_VPN)
            } else {
                startVpnService()
            }
        }
    }

    private fun applyButton() = Button(this).apply {
        text = "Apply routing"
        setOnClickListener {
            Toast.makeText(this@MainActivity, "Applying…", Toast.LENGTH_SHORT).show()
            applyRouting()
        }
    }

    private fun cleanupButton() = Button(this).apply {
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

    /**
     * Kills every enabled client so the next launch is injected cleanly. A client only consults the
     * injector at process start, so this is the way to pick up a registry change without rebooting
     * anything.
     */
    private fun reinjectButton() = Button(this).apply {
        text = "Reinject clients (force-stop)"
        setOnClickListener {
            val clients = ClientRegistry.enabled(this@MainActivity).map { it.packageName }
            if (clients.isEmpty()) {
                Toast.makeText(this@MainActivity, "No clients enabled", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Toast.makeText(this@MainActivity, "Stopping ${clients.size} client(s)…", Toast.LENGTH_SHORT).show()
            Thread {
                Routing.forceStop(clients) { line -> android.util.Log.i("avpnp", line) }
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "Clients stopped — reopen them to reinject",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }.start()
        }
    }

    private fun applyRouting() {
        Thread {
            Routing.apply(this) { line -> android.util.Log.i("avpnp", line) }
        }.start()
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

    /** Flag changes take effect the next time a client prepares/establishes. */
    private fun publishFlags() {
        Thread {
            FlagPublisher.sync(this) { line -> android.util.Log.i("avpnp", line) }
        }.start()
    }
}
