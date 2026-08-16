# Suite-B-192 hotspot integration

This branch integrates the experimentally verified Xiaomi Mi 9 (`cepheus`) WPA3-Enterprise 192-bit access point into VPN Hotspot without replacing VPN Hotspot's routing core.

## Verified device-side baseline

The working standalone topology is:

- upstream: `wlan0`
- custom AP: `wlan2`
- downstream IPv4 gateway: `192.168.77.1/24`
- DHCP pool: `192.168.77.20` - `192.168.77.200`
- SSID: `MI9-SuiteB192-Lab`
- BSSID: `6a:66:77:88:99:a8`
- hostapd key management: `WPA-EAP-SUITE-B-192`
- pairwise/group cipher: `GCMP-256`
- group management cipher: `BIP-GMAC-256`
- PMF: required
- EAP: EAP-TLS

The current proof-of-concept hostapd installation is external to the APK:

- `/data/local/tmp/mi9-enterprise/hostapd`
- `/data/local/tmp/mi9-enterprise/suiteb192/suiteb192.conf`
- `/data/local/tmp/mi9-enterprise/termux_libdir`

This is intentionally the first integration target. Bundling hostapd and PKI lifecycle into the APK is a later phase.

## Current app integration

The Tethering screen contains a first-class `WPA3-Enterprise 192-bit` switch. A matching toggleable Quick Settings tile is also registered. Both drive `SuiteB192HotspotService`.

The service starts the root-side AP/DHCP state first, then attaches `wlan2` to `RoutingManager.LocalOnly`. The shared VPN Hotspot routing core therefore remains responsible for upstream/VPN selection, client policy, masquerade, DNS interception and IPv6 behavior.

Boot auto-start is intentionally not registered for this experimental service yet. The external hostapd currently depends on the Termux library directory under credential-encrypted application storage, so starting this path during Direct Boot would not be reliable.

## Integration ownership

### SuiteB192HotspotService owns

- creating/removing its `wlan2`
- starting/stopping its custom hostapd process
- assigning `192.168.77.1/24`
- starting Android legacy dnsmasq for DHCP
- adding/removing `wlan2` from Android `local_network`
- adding/removing `192.168.77.0/24` from `local_network`
- exposing active interface state to Compose/UI and Quick Settings
- creating a `RoutingManager.LocalOnly` instance for `wlan2`

### VPN Hotspot routing continues to own

- upstream selection
- forwarding policy
- client admission and accounting
- masquerade mode
- VPN routing
- DNS interception/proxying
- IPv6 policy
- cleanup through `RoutingManager.clean()`

Do not duplicate VPN Hotspot's routing rules in the Suite-B service.

## Why DHCP stays separate

VPN Hotspot's routing daemon is not a DHCP server. The working baseline already proves Android's legacy tethering dnsmasq can serve DHCP on `wlan2`. For this integration phase it is retained only as the address-allocation mechanism.

VPN Hotspot's own DNS path remains authoritative for routed clients. The Suite-B service therefore does not use the standalone proof-of-concept's `ndc tether dns set ...` step.

Stopping Suite-B removes only `wlan2` from legacy tethering. It deliberately does **not** issue global `ndc tether stop`, because the legacy tether service is shared Android state and may also be serving another downstream.

## Root ownership and interrupted startup

The root command uses two deterministic markers under `/data/local/tmp/mi9-enterprise`:

- `vpnhotspot-suiteb192.pid` identifies the hostapd process; before sending a signal the process command line must still contain the exact Suite-B config path.
- `vpnhotspot-suiteb192.owner` records the current Linux boot ID and the `wlan2` ifindex. Interface deletion additionally requires the fixed Suite-B BSSID to match.

This prevents a stale PID file surviving a reboot from authorizing deletion of a new or platform-owned `wlan2`. A current-boot owner marker with matching ifindex/BSSID permits same-boot recovery after an interrupted app/service lifecycle. A foreign `wlan2` without a matching current-boot owner marker causes startup to fail explicitly instead of being deleted.

The owner file is first written with a `pending` state immediately before interface creation and then replaced with `<boot-id> <ifindex>` after the interface exists and its BSSID has been assigned. Normal startup failures are protected by a shell trap and roll back the committed root state. A process killed in the extremely small pending interval is intentionally handled conservatively on the next start: the app refuses to claim an unverified existing interface rather than deleting it.

## Lifecycle invariants

1. Starting the service is idempotent for a surviving app-owned AP.
2. `wlan2` is never deleted merely because an old PID filename exists.
3. A persistent interface is considered app-owned only when boot ID, ifindex and BSSID match the owner marker.
4. Root-side mutations have deterministic inverse operations scoped to Suite-B-owned state.
5. Normal stop removes the local-network route/interface membership and DHCP tether registration before deleting the owned `wlan2`.
6. Interrupted startup rolls back mutations already committed when ownership can be proven.
7. App-level Clean remains responsible for VPN Hotspot routing state; Suite-B-specific AP/DHCP state does not duplicate routing rules.
8. Missing external hostapd/config files and ownership conflicts fail explicitly.

## Implementation phases

### Phase 1: external-hostapd integration — current checkpoint

Implemented:

- foreground `SuiteB192HotspotService`
- root AP/DHCP/local-network commands using the verified external hostapd/config
- `RoutingManager.LocalOnly` attachment for `wlan2`
- Tethering-screen switch
- Quick Settings tile and client count
- deterministic same-boot ownership/cleanup markers

Still required before calling Phase 1 validated: compile/CI validation and an on-device end-to-end test from the new app switch.

### Phase 2: packaged runtime

Package a known hostapd build in the app or provision it into app-owned device-protected storage through the root process. Replace the Termux runtime dependency and hard-coded laboratory PKI with an app-managed certificate workflow.

### Phase 3: configuration UI

Expose SSID, channel, downstream subnet, server identity/certificate, client trust material, and Suite-B-192 status while preserving the strict cipher/AKM requirements.

## Compatibility scope

The branch is deliberately device-specific. It targets the already verified Android 11 / Xiaomi Mi 9 environment and should be treated as experimental until capability detection replaces the fixed `phy0`/`wlan2` assumptions.
