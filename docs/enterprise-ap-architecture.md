# Enterprise AP extension architecture

This branch adds WPA2/WPA3 Enterprise hotspot support as an extension layer around VPN Hotspot instead of forking its routing core.

## Design goals

1. Keep upstream merge conflicts small and predictable.
2. Reuse VPN Hotspot for upstream selection, VPN routing, masquerade, DNS interception, client policy, statistics and cleanup.
3. Keep Android `SoftApConfiguration` untouched for Enterprise modes because the Android framework API does not represent the custom hostapd configuration required by the target device.
4. Put Enterprise-specific state, hostapd rendering, runtime provisioning and credential management under `be.mygod.vpnhotspot.enterprise`.
5. Keep the existing AP configuration UI as the user-facing entry point. Enterprise security choices appear beside Open/WPA2-PSK/WPA3-SAE rather than as a separate hotspot feature.
6. Keep the hostapd backend replaceable. UI/domain/routing code does not depend on binary packaging details.

## Upstream touch points

Long-lived changes to upstream-owned files are intentionally limited to adapters:

- `ui/apconfiguration/ApConfigurationState.kt`: exposes Enterprise security choices and holds the screen-local Enterprise selection.
- `ui/apconfiguration/ApConfigurationScreen.kt` and related rows: shows Enterprise users when an Enterprise mode is selected.
- `net/TetheringManagerCompat.kt`: intercepts Wi-Fi tether start/stop only when an Enterprise profile is configured.

Everything else lives in new Enterprise-specific files. If upstream rewrites its AP UI or tethering entry point, only these adapters should normally require rebasing.

## Domain model

`EnterpriseApProfile` is independent of Compose, Android Wi-Fi framework classes and root commands. Stable string IDs are used for persisted modes rather than Android integer security constants.

Initial modes:

- `WPA2_ENTERPRISE`: integrated EAP server, PEAP + MSCHAPv2, multiple username/password credentials.
- `WPA3_ENTERPRISE`: integrated EAP server, PEAP + MSCHAPv2, mandatory PMF.
- `WPA3_ENTERPRISE_192`: reserved for EAP-TLS / Suite-B-192 and certificate identities.

The first user-facing implementation exposes WPA2-Enterprise and WPA3-Enterprise. The 192-bit mode remains an advanced follow-up.

## Credential storage

UI code talks to an `EnterpriseProfileRepository` interface. Callers do not depend on a particular storage format, allowing future migration to stronger credential storage without changing the AP UI or hostapd renderer.

Passwords are never intentionally written to logs. Generated session files live in app-owned private storage.

## Hostapd rendering

`HostapdEnterpriseConfig` is a pure renderer. It receives a validated profile plus common AP parameters and produces the Enterprise-specific hostapd configuration and the `eap_user` database.

The renderer does not start processes and does not know Android interface ownership. This keeps authentication policy unit-testable and independent from device lifecycle code.

## Bundled runtime

The device-verified arm64 runtime is shipped as:

`mobile/src/main/assets/enterprise/arm64-v8a/mi9-hostapd-runtime.tar.gz`

`EnterpriseBundledRuntime` extracts only a fixed whitelist of required files and verifies each file against a pinned SHA-256 digest. During the first bundled-runtime validation phase, `EnterpriseBundledRuntimeInstaller` materializes those verified files into the runtime location currently consumed by `EnterpriseApCommands`. Once device validation passes, the remaining legacy/Termux discovery code can be removed without changing UI/profile/routing layers.

The runtime contains hostapd plus its private libnl/OpenSSL dependencies. No Termux package is required for the bundled runtime path.

The APK runtime is currently arm64-v8a-specific. This limitation is isolated inside `EnterpriseBundledRuntime`/`EnterpriseHostapdRuntime`; adding another ABI does not require changes to UI/profile/routing code.

## Runtime lifecycle

`EnterpriseHostapdRuntime` performs the following sequence:

1. validate AP/profile state;
2. generate app-owned EAP users, server PKI and hostapd session configuration;
3. verify/extract the bundled runtime;
4. materialize the verified runtime for the root-owned hostapd process;
5. create `wlan2`, set the owned BSSID, start hostapd and attach Android DHCP/local-network plumbing;
6. expose `wlan2` to `RoutingManager.LocalOnly` through `EnterpriseHotspotService`.

The Enterprise layer deliberately does not implement its own upstream selection, VPN policy, masquerade, DNS interception or client-routing engine.

## Platform configuration carrier

Enterprise security is not encoded into `SoftApConfigurationCompat.securityType`. Common AP fields such as SSID/channel/BSSID continue to use the existing screen state, while Enterprise mode/profile is carried separately. This prevents unsupported Enterprise values from being written into Android `SoftApConfiguration`.

## Rebase policy

Keep `enterprise-ap-integration` synchronized with upstream `master` frequently. Never copy or fork the routing daemon merely for Enterprise support. Prefer a narrow adapter around an upstream entry point.

Before an upstream merge is considered ready:

1. merge/rebase upstream master;
2. run the upstream test workflow unchanged;
3. compile Enterprise extension tests;
4. validate the AP configuration adapter;
5. run Mi 9 device smoke tests for WPA2-Enterprise and WPA3-Enterprise.

The old `suiteb192-phase1-checkpoint` branch remains a recovery/reference checkpoint only and is not part of the current APK runtime implementation.
