# Enterprise AP extension architecture

This branch adds WPA2/WPA3 Enterprise hotspot support as an extension layer around VPN Hotspot instead of forking its routing core.

## Design goals

1. Keep upstream merge conflicts small and predictable.
2. Reuse VPN Hotspot for upstream selection, VPN routing, masquerade, DNS interception, client policy, statistics and cleanup.
3. Keep Android `SoftApConfiguration` untouched for Enterprise modes because the Android framework API does not represent the custom hostapd configuration we need on the target device.
4. Put Enterprise-specific state, hostapd rendering, runtime provisioning and credential management under one package: `be.mygod.vpnhotspot.enterprise`.
5. Keep the existing AP configuration UI as the user-facing entry point. Enterprise security choices should appear beside Open/WPA2-PSK/WPA3-SAE rather than as a separate hotspot feature.
6. Make the runtime replaceable. UI/domain code must not know whether hostapd comes from an APK asset, an extracted app-owned runtime, or a future platform capability.

## Upstream touch points

The target is to keep long-lived changes to upstream-owned files limited to adapters:

- `ui/apconfiguration/ApConfigurationState.kt`: expose Enterprise security choices and hold the screen-local Enterprise selection.
- `ui/apconfiguration/ApConfigurationScreen.kt`: show Enterprise user/certificate rows when an Enterprise mode is selected.
- `ui/VpnHotspotApp.kt` / the Wi-Fi tethering start path: delegate save/start/stop to the Enterprise extension when Enterprise is selected.

Everything else belongs in new files. If upstream rewrites its AP UI, only these adapters should need rebasing.

## Domain model

`EnterpriseApProfile` is independent of Compose, Android Wi-Fi framework classes and root commands. Stable string IDs are used for persisted modes rather than Android integer security constants.

Initial modes:

- `WPA2_ENTERPRISE`: integrated EAP server, PEAP + MSCHAPv2, multiple username/password credentials.
- `WPA3_ENTERPRISE`: integrated EAP server, PEAP + MSCHAPv2, mandatory PMF; exact hostapd AKM/cipher policy stays in the renderer/runtime layer.
- `WPA3_ENTERPRISE_192`: reserved for EAP-TLS / Suite-B-192 and certificate identities. It is intentionally not modeled as username/password authentication.

The first user-facing implementation exposes WPA2-Enterprise and WPA3-Enterprise. The 192-bit mode remains an advanced follow-up.

## Credential storage

UI code talks to an `EnterpriseProfileRepository` interface. The first implementation may use app-private preferences, but callers must not depend on that storage format. This lets us replace it with an Android Keystore-backed implementation without changing the AP UI or hostapd renderer.

Passwords are never written to logs. Generated runtime files live in app-owned/root-owned private storage and are created with restrictive permissions.

## Hostapd rendering

`HostapdEnterpriseConfig` is a pure renderer. It receives a validated profile plus common AP parameters and produces:

- the Enterprise-specific hostapd fragment;
- an `eap_user` file for PEAP/MSCHAPv2 users.

The renderer does not start processes and does not know Android interface ownership. This makes it unit-testable and lets runtime code change independently.

For PEAP/MSCHAPv2, the EAP user database contains a wildcard PEAP phase-1 entry followed by one enabled MSCHAPV2 phase-2 entry per account. The hostapd runtime must include integrated EAP, TLS, PEAP and MSCHAPv2 support.

## Runtime boundary

A future `EnterpriseApRuntime` interface owns:

- provisioning a known hostapd build and its libraries into app-owned private storage;
- server CA/certificate/key lifecycle;
- creating/removing the AP interface;
- writing generated hostapd/eap_user files;
- starting/stopping hostapd;
- exposing the downstream interface to `RoutingManager`;
- deterministic rollback on partial startup.

It must not duplicate VPN Hotspot's routing/NAT/DNS implementation.

## Platform configuration carrier

Enterprise security is not encoded into `SoftApConfigurationCompat.securityType`. Common AP fields (SSID/channel/BSSID/etc.) can continue to use the existing screen state, while Enterprise mode/profile is carried separately by the extension model. This avoids inventing invalid Android security constants and prevents accidental calls to `WifiManager.setSoftApConfiguration()` with an unsupported Enterprise value.

## Rebase policy

Keep `enterprise-ap-integration` rebased or merged from upstream `master` frequently. Never edit upstream routing daemon code unless an Enterprise downstream exposes a real missing abstraction. Prefer adding a narrow interface/adapter over copying an upstream class.

Before every upstream merge:

1. merge/rebase upstream master;
2. run upstream test workflow unchanged;
3. compile Enterprise extension tests;
4. validate the AP configuration adapter;
5. run Mi 9 device smoke tests for WPA2-Enterprise and WPA3-Enterprise.

The old `suiteb192-phase1-checkpoint` branch remains a device-verified recovery reference and is not the base for this architecture.