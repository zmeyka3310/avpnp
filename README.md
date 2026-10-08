# avpnp — Android VPN Patchbay

A Helvum-style patchbay for Android networking: draw a graph of apps, VPN clients, and exits; the router
compiles it into kernel routing state.

## The problem

Android permits exactly one `VpnService` per user profile. That makes it impossible to run several VPN
clients side by side, and therefore impossible to route different apps through different VPNs at the same
time, or to chain one VPN's exit into another VPN's entry.

Existing tools work around this with UID-based routing tables. Tables are a poor interface for the shape of
the problem: fan-in, fan-out, nesting, and chaining are natural in a graph and awkward in rows.

## The idea

Every participant is a node with typed ports:

| Node | Ports | Role |
|---|---|---|
| **App** | one output | origin of plaintext traffic |
| **VPN** | one input, one output | transducer: plaintext in, ciphertext out |
| **Uplink** | one input | the only thing that physically exits the phone |

A **link** is a cable from one output port to one input port. A VPN client is not special: everything after
its exit port is an ordinary socket owned by its UID, indistinguishable from a normal app's socket. That is
what makes chaining and fan-in ordinary routing problems.

## How it works

- **avpnp owns the one VPN slot.** It establishes its own `VpnService`, so Android is satisfied and no other
  app can steal or displace it.
- **Hijacked clients never bind.** One generic LSPosed hook intercepts the framework methods every
  cooperative client shares — `VpnService.prepare()` and `VpnService.Builder.establish()` — and answers them
  so the client believes it is running normally.
- **avpnp creates the client's TUN.** A small root helper opens `/dev/net/tun`, configures the interface, and
  hands the fd back. avpnp brokers that fd to the client over binder, and the client bridges it to its own
  core as it would its own TUN.
- **The injector is gated by a flag.** avpnp publishes `/data/local/tmp/avpnp.routing`. While that
  file is absent the injected code substitutes nothing and the client runs its normal logic, so a
  stray injection cannot break a client. The file is labelled `system_file` so apps may read it.
- **avpnp routes by UID.** Each link compiles to one `ip rule` sending a source UID's packets to an exit's
  route table. The kernel moves every packet; avpnp performs no userspace packet processing.

The practical result: several VPN clients can be connected at once, each carrying a different set of apps,
which Android alone does not allow. The full design, the reverse engineering behind it, and the current
limitations are in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Status

Working, early. On the test device **two VPN clients run simultaneously**, each carrying a different app,
with avpnp owning the single system VPN slot:

- **qWDTT** is hijacked, receives its own TUN (`avpnp_qw0`, MTU 1300), and carries Telegram.
- **Happ** is hijacked, receives its own TUN (`avpnp_hp0`, MTU 1500), and carries Telegram X.
- Both interfaces report **zero drops**, and the only rules installed are exactly the two intended links
  (`12500 uidrange 10501 -> 2002`, `12501 uidrange 10478 -> 2003`, in both IPv4 and IPv6).

Android's one-VPN-per-profile limit is genuinely bypassed. **Outline** is intercepted correctly and given a
working TUN, but its relay does not drain the interface on this setup (TX drops, ~0% CPU), so it is
currently disabled in the profile list.

Not yet implemented: the graph editor, the compiler, the `throw`-based default-exit behaviour that makes
avpnp a real full-tunnel VPN, and multi-hop chaining. Routing is currently expressed directly in
`AvpnpConfig`.

## AI transparency

avpnp distinguishes clearly between human direction and AI implementation:

- **Ideation, architecture, and design decisions are human-made.** The project maintainer designs the
  system, sets its technical and ideological direction, and has final say over every decision. AI does not
  set direction.
- **Implementation is AI-written.** Code is produced by **DeepSeek v4.1 Flash**, driven through the
  **DeepSeek Harness (DSH)**. This covers the whole implementation, including the core (graph store,
  compiler, apply layer), not only the UI.
- **The UI and the reverse engineering are specifically AI work.** The on-device reverse engineering of
  Android's VPN and routing internals, and of the supported VPN clients, was performed by AI, as is the
  Compose UI.
- AI implements against human-specified architecture and constraints. Where a design question is open, it
  is asked and decided by the maintainer, not assumed by the AI.

## Non-goals

- No userspace packet processing; the kernel is the only data plane.
- No decapsulation or re-encapsulation, and no traffic inspection.
- No failover, health checks, or automatic reconnection.
- No validation beyond making cycles and dangling ports visible.
- No support for uncooperative apps.

## License

Released into the public domain under the [Unlicense](LICENSE).
