package dev.zmeyka.avpnp

/**
 * avpnp routing intent.
 *
 * Deliberately client-agnostic: supporting another VPN client is a data change (add a profile),
 * never a code change. Nothing here reads the client's own configuration — the routing decision
 * belongs to avpnp.
 *
 * Each profile describes one hijacked client: which apps it carries, and the TUN avpnp creates for
 * it. All of it is avpnp's decision; the client only ever sees the fd.
 */
object AvpnpConfig {

    data class ClientProfile(
        /** Package of the VPN client to hijack. */
        val packageName: String,
        /** Human label, for the UI and logs. */
        val label: String,
        /** Packages routed through this client; everything else exits directly. */
        val routedApps: List<String>,
        /** Name of the TUN interface avpnp creates for this client. `%d` lets the kernel pick a
         *  unique instance number, so a reconnect cannot collide with a still-held interface. */
        val tunName: String,
        /** Address assigned to that TUN, in CIDR form. Per-client; MTUs and addresses may differ. */
        val tunAddress: String,
        /** MTU for that TUN, as the client expects it. */
        val tunMtu: Int,
        /** avpnp-owned routing table carrying `default dev <tunName>`. */
        val table: Int,
        /** Default for the runtime routing toggle; the app can change it per client. */
        val routingEnabledByDefault: Boolean = true,
    )

    val profiles: List<ClientProfile> = listOf(
        ClientProfile(
            packageName = "net.qwdtt.client",
            label = "qWDTT",
            routedApps = listOf("org.telegram.messenger"),
            tunName = "avpnp_qw%d",
            tunAddress = "10.70.1.209/32",
            tunMtu = 1300,
            table = 2002,
        ),

        ClientProfile(
            packageName = "com.happproxy",
            label = "Happ",
            routedApps = listOf("org.thunderdog.challegram"),
            tunName = "avpnp_hp%d",
            tunAddress = "26.26.26.1/30",
            tunMtu = 1500,
            table = 2003,
        ),

        // Outline is intercepted correctly and given a working TUN, but its relay does not drain the
        // interface on this setup (TX drops, ~0% CPU). Left disabled so it cannot blackhole traffic.
        // ClientProfile(
        //     packageName = "org.outline.android.client",
        //     label = "Outline",
        //     routedApps = listOf("com.discord"),
        //     tunName = "avpnp_outline",
        //     tunAddress = "10.111.222.1/24",
        //     tunMtu = 1500,
        //     table = 2001,
        // ),
    )

    /** First priority avpnp uses; one priority per routed app. Kept below netd's 13000 VPN band. */
    const val RULE_PRIORITY_BASE = 12500

    /** Last priority avpnp owns. Everything in [RULE_PRIORITY_BASE]..this is avpnp's to flush. */
    const val RULE_PRIORITY_END = 12999

    /**
     * Temporary milestone switch. While true the module answers `establish()` with a synthesized
     * socketpair fd instead of asking avpnp's broker. Kept as a fallback for bringing a client up
     * when the broker is unavailable.
     */
    const val USE_FAKE_TUN = false

    fun profileFor(packageName: String): ClientProfile? =
        profiles.firstOrNull { it.packageName == packageName }
}
