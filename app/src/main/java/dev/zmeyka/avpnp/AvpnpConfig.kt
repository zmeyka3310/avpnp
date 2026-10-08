package dev.zmeyka.avpnp

/**
 * Constants shared by the compiler and the injector.
 *
 * The client list is deliberately NOT here: clients are registered at runtime in [ClientRegistry],
 * so their number is open-ended.
 */
object AvpnpConfig {

    /** First priority avpnp uses; one priority per routed app. Kept below netd's 13000 VPN band. */
    const val RULE_PRIORITY_BASE = 12500

    /** Last priority avpnp owns. Everything in [RULE_PRIORITY_BASE]..this is avpnp's to flush. */
    const val RULE_PRIORITY_END = 12999
}
