package dev.zmeyka.avpnp;

import android.os.ParcelFileDescriptor;

/**
 * Privileged services avpnp offers to a hijacked VPN client's process. The module injected into the
 * client calls into this broker instead of using root itself, so only avpnp needs root.
 *
 * Visibility: avpnp is installed with `--force-queryable`, which sets the adb force-queryable
 * override in AppsFilter, so clients may bind without patching them or hooking system_server.
 */
interface IAvpnpBroker {

    /** Create the client's TUN and return its fd, or null on failure. */
    ParcelFileDescriptor acquireTun(String clientPackage);

    /** Install avpnp's routing state (ip rules) for the configured profiles. */
    void applyRouting();
}
