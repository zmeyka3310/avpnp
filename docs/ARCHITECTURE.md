# avpnp Architecture

This document describes the design **and the current working implementation**. Where the two differ, it
says so explicitly. avpnp is a control plane: it decides where packets go and installs kernel state to make
it so. It never moves packets itself.

## 1. Components

| Component | Responsibility |
|---|---|
| **UI app** (`dev.zmeyka.avpnp`) | Owns the graph document (future). Currently shows config and applies routing. |
| **AvpnpVpnService** | Holds the single system VPN slot for the profile. |
| **AvpnpBrokerService** | AIDL bound service. Creates per-client TUNs and returns their fds; installs routing. |
| **`libavpnp_tun.so`** | Static aarch64 root helper. Opens `/dev/net/tun`, configures the interface, hands back the fd. |
| **LSPosed module** | One generic hijack, injected into supported clients. No per-client code. |
| **VPN clients** | Unmodified. They perform crypto and own their socket to their server. |
| **Kernel** | The entire data plane. |

## 2. The fd chain

Per client, on `establish()`:

```
LSPosed module (in client process)
   |  bindService -> avpnp broker (AIDL)
   v
AvpnpBrokerService.acquireTun(clientPackage)        [uid-checked]
   |  su -c libavpnp_tun.so --connect <abstract sock> --if avpnp_<client> --mtu .. --addr .. --table ..
   v
root helper (ksu domain)                            [opens /dev/net/tun, TUNSETIFF, ip ...]
   |  SCM_RIGHTS over the abstract socket
   v
avpnp receives the TUN fd -> ParcelFileDescriptor -> binder
   v
module returns it from establish()
   v
client's own core reads/writes that TUN
```

avpnp never reads or writes the TUN. It is a duplicate of a kernel fd; the client is the only reader.

## 3. Hook surface

One generic hook, driven by per-package profiles. Verified as sufficient for all three target clients.

1. `VpnService.prepare(Context)` -> `null`, so the client believes permission is already granted.
   (Both Happ and Outline gate on this and bail out if it returns non-null.)
2. `VpnService.Builder.establish()` -> a `ParcelFileDescriptor` over avpnp's per-client TUN.
3. The client's `VpnService` instance is captured via the constructor hook, because it is a `Context`.
4. The broker is pre-bound on the main looper when that instance is constructed — not in the constructor
   itself (the `Context` is not attached yet) — so `establish()` never blocks the client's service thread
   past its `startForeground()` deadline.
5. `onRevoke()` must not tear down avpnp; the client's own teardown closes only its fd copy.

Client settings (routes, app filters, DNS) are ignored as configuration, but the TUN is created with the
address and MTU the client asked for, so it sees the interface it expects.

## 4. Routing

One link becomes one rule:

| Graph | Compiled state |
|---|---|
| `X.out -> Y.in` | `ip rule add prio <P> uidrange <uid_X> lookup <tbl_Y>` and `tbl_Y: default dev avpnpY` |
| `X.out -> uplink` | no rule — traffic takes netd's normal path |

- **Per VPN node `Y`:** the root helper creates `avpnpY` and the table route; avpnp owns the config
  (per-client address, MTU, and table).
- **Per link:** one rule at `12500 + n`, installed for **IPv4 and IPv6**.
- **Reapply** is delete-then-add, so it is idempotent. No transactions, no rollback.
- **No iptables, no DNAT, no conntrack manipulation, no userspace forwarding.**

The current implementation reads its links directly from `AvpnpConfig`. The graph/compiler layer replaces
that source but not the compiled form.

## 5. Android constraints discovered on-device

These shaped the design and are worth recording, because each closed off an obvious approach.

- **App-to-app Unix sockets are impossible.** SELinux assigns each app its own MLS category
  (`untrusted_app:s0:c6,...` vs `:c14,...`), and `unix_stream_socket connectto` requires matching
  categories. No socket path, permission, or file location changes that. Both directions fail.
- **Binder needed visibility, not root.** Android hides other apps' packages by default; `bindService`
  failed with `AppsFilter: ... BLOCKED` / `Unable to start service ... not found`.
- **`--force-queryable` fixes that cleanly.** `AppsFilterImpl` honours
  `newPkgSetting.isForceQueryableOverride()` (the adb override) with no system-app gating, and
  `pm install --force-queryable` sets it. avpnp is therefore installed with that flag. This avoided patching
  client APKs to add `<queries>`, and avoided hooking `system_server` (which would conflict with modules
  such as Hide My Applist). The manifest `android:forceQueryable` attribute alone would not work: it is
  gated on the app being a system app.
- **`ksu` can hand fds to app processes.** A root-domain process may connect where an app may not, so the
  helper -> avpnp hop works even though app -> app does not.
- **Netd's rule bands** (IPv4 and IPv6 identical): `0` local, `10000` legacy_system, `11000` per-interface,
  `12000` VPN local_network, **`13000` VPN uidrange**, `15040` per-app binding, `16000`-`20000` network
  selection, `22040`/`23000` default network, `25000`-`31000` fallbacks, `32000` unreachable. avpnp's band
  (`12500+`) sits below the VPN family so it wins for the UIDs it claims.
- **Network tables** are named after the interface, with ids of the form `1000+ifindex`; `<name>_local` is
  `1000000000+id`. avpnp's tables use the `2001+` range.

## 6. On-device baseline

Nubia NX789J, Android 16 (SDK 36, kernel 6.6.92, aarch64), KernelSU root under `u:r:ksu:s0`, SELinux
enforcing, Zygisk-LSPosed. `CONFIG_TUN=y`; `/dev/net/tun` present; `TUNSETIFF` proven from the ksu domain.
`/system/bin/ip` is full iproute2.

## 7. Current status and limitations

**Verified working, two clients at once.** qWDTT carries Telegram and Happ carries Telegram X, each through
its own avpnp-created TUN, with avpnp owning the system VPN slot:

```
interfaces:  avpnp_qw0 (MTU 1300)   avpnp_hp0 (MTU 1500)   tun0 (avpnp's slot, idle)

rules:       12500 uidrange 10501 -> 2002   (Telegram   -> qWDTT)
             12501 uidrange 10478 -> 2003   (Telegram X -> Happ)
             installed for IPv4 and IPv6, and nothing else in the band

counters:    avpnp_qw0   rx 681229 B / 804 pkts   tx 260966 B / 1058 pkts   drops 0
             avpnp_hp0   rx 636394 B / 868 pkts   tx 129487 B / 944  pkts   drops 0
```

**Current limitations, stated plainly:**

- **avpnp's own VPN is a no-op.** It establishes with an allowlist containing only avpnp itself, so `tun0`
  carries nothing. Making it a real full-tunnel VPN requires replacing its table's default route with
  `throw` so unassigned traffic falls back through netd to the physical uplink. Designed, not implemented.
- **Outline stalls.** It is intercepted correctly and given a working TUN, but its relay does not drain the
  interface (TX drops, ~0% CPU), and routed apps do not connect through it. The same plumbing is clean for
  qWDTT and Happ, so this is Outline-specific and unresolved.
- **Root is required** for the per-client TUNs. A rootless mode is possible later: avpnp's own framework TUN
  can be lent to a single client through the same broker interface.
- **Tethering** is out of scope for now: forwarded traffic is not locally generated, so `iif lo` rules do
  not classify it.

### Operational notes

- TUNs are named with `%d` (`avpnp_qw%d`, `avpnp_hp%d`) so the kernel assigns a unique instance per
  creation. A fixed name collides (`EBUSY` on `TUNSETIFF`) whenever a client reconnects while its previous
  core still holds the interface.
- avpnp drops its own copy of a TUN fd shortly after the binder reply, so the interface's lifetime follows
  the client's fd rather than avpnp's.
- Reapplying routing flushes the entire priority band before re-adding, so links removed from the config do
  not linger as stale rules.
- Clients must be force-stopped after installing a new module build; a client process that survives an
  avpnp reinstall would otherwise keep a failed bind, though the module now retries one.

## 8. Configuration

`AvpnpConfig.profiles` is the current source of truth: one entry per client, each naming the apps it
carries, its TUN name/address/MTU, and its routing table. Adding a client is a data change, not a code
change. The graph store and compiler will replace this list as their input.
