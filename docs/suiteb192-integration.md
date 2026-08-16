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

## Integration ownership

### SuiteB192HotspotService should own

- creating/removing `wlan2`
- starting/stopping the custom hostapd process
- assigning `192.168.77.1/24`
- starting Android legacy dnsmasq for DHCP
- adding/removing `wlan2` from Android `local_network`
- adding/removing `192.168.77.0/24` from `local_network`
- exposing active interface state to Compose/UI
- creating a `RoutingManager.LocalOnly` instance for `wlan2`

### VPN Hotspot routing should continue to own

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

VPN Hotspot's routing daemon is not a DHCP server. The working baseline already proves Android's legacy tethering dnsmasq can serve DHCP on `wlan2`. For the first integration phase it is retained only as the address-allocation mechanism.

VPN Hotspot's own DNS path should remain authoritative for routed clients. The Suite-B service therefore must not depend on `ndc tether dns set` for normal operation once VPN Hotspot routing is active.

## Lifecycle invariants

1. Starting the service must be idempotent.
2. `wlan2` must not be deleted unless this service owns the custom hostapd instance.
3. Root-side mutations must have deterministic inverse operations.
4. Normal stop must remove the local-network route/interface membership and DHCP tether registration before deleting `wlan2`.
5. Interrupted startup must roll back every mutation already committed.
6. App-level Clean must remain safe; Suite-B-specific state must not require private persistent bookkeeping to repair routing.
7. The first implementation may require the external proof-of-concept files listed above, but failure must be explicit if they are missing.

## Initial implementation phases

### Phase 1: external-hostapd integration

Add a foreground `SuiteB192HotspotService` and root commands that use the existing external hostapd/config. Add one toggle row to the tethering screen. Once `wlan2` is live, attach `RoutingManager.LocalOnly` so VPN Hotspot owns routing.

### Phase 2: packaged runtime

Package a known hostapd build in the app or install it into app-owned device-protected storage through the root process. Replace hard-coded laboratory PKI with an app-managed certificate workflow.

### Phase 3: configuration UI

Expose SSID, channel, downstream subnet, server identity/certificate, client trust material, and Suite-B-192 status while preserving the strict cipher/AKM requirements.

## Compatibility scope

The first branch is deliberately device-specific. It targets the already verified Android 11 / Xiaomi Mi 9 environment and should be treated as experimental until capability detection replaces the fixed `phy0`/`wlan2` assumptions.
