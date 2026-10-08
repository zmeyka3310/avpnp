package dev.zmeyka.avpnp

/**
 * Everything avpnp needs to create and wire one client's tunnel. Assigned automatically when a
 * client is enabled, so the number of clients is open-ended.
 *
 * Encoded as a single string for the AIDL boundary: `tunName|address|mtu|table`.
 */
data class ClientConfig(
    val packageName: String,
    val tunName: String,
    val address: String,
    val mtu: Int,
    val table: Int,
) {
    fun encode(): String = "$tunName|$address|$mtu|$table"

    companion object {
        fun decode(packageName: String, encoded: String): ClientConfig? {
            val parts = encoded.split("|")
            if (parts.size != 4) return null
            val mtu = parts[2].toIntOrNull() ?: return null
            val table = parts[3].toIntOrNull() ?: return null
            return ClientConfig(packageName, parts[0], parts[1], mtu, table)
        }
    }
}
