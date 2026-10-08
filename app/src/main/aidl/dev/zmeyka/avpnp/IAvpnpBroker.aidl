package dev.zmeyka.avpnp;

import android.os.ParcelFileDescriptor;

/**
 * The only thing that crosses binder between avpnp and a hijacked client is a file descriptor.
 *
 * All routing policy lives in the world-readable config at /data/local/tmp/avpnp.conf, which the
 * injector reads directly.
 *
 * Visibility: avpnp is installed with the force-queryable override in AppsFilter, so clients may
 * bind without patching them or hooking system_server.
 */
interface IAvpnpBroker {

    /** Create the named client's TUN and return its fd, or null on failure. */
    ParcelFileDescriptor acquireTun(String clientPackage);

    /** Recompile avpnp's kernel routing state on demand. */
    void applyRouting();
}
