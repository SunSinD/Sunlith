# Connection UX research (2026-11-06)

Why: the paired screen (screenshot reviewed) showed *Connection on / Stop connection / Send pairing file*
with no obvious exit, and first pairing forced *Save pairing file* (a document picker) before any sharing.
This note records the integrated phone, companion and widget behavior and the official
sources behind it.

## Answer in one paragraph

The phone shares a connection file directly through Android's share list using a narrow,
unexported FileProvider. With Tailscale, choose Tailscale and then your computer; with
local Wi-Fi, use a private transfer method to that computer. The one-time steps are to
choose calendars, tap Connect computer, share the file, and start the connection. Later
use keeps that authorization. Tailscale supplies network access; the scoped token and
pinned certificate supply Sunlith authorization. Automatic peer discovery would not
replace that authorization, and no silent discovery or transfer is implemented.

## 1. Tailscale VPN ≠ Sunlith authorization — keep explicit pairing

- Tailscale identity is user identity (from an external OIDC IdP) plus a cryptographic **node** identity;
  ACLs/grants decide which nodes may open network connections to which ports. Application-layer
  capabilities exist, but *“applications are responsible for … implementing the logic”* and Tailscale treats
  capability parameters as opaque — i.e. the app itself must authenticate/authorize the requester.
  ([Tailscale identity](https://tailscale.com/docs/concepts/tailscale-identity),
  [Grants](https://tailscale.com/docs/features/access-control/grants),
  [Application capabilities](https://tailscale.com/docs/features/access-control/grants/grants-app-capabilities),
  [ACLs](https://tailscale.com/docs/features/access-control/acls))
- Consequence for Sunlith: “the computer is on my tailnet and can reach `100.x:39393`” proves network
  reachability of *some* device of an authorized user. It does not prove this Sunlith install authorized
  *calendar reads/writes*, which calendars, or revocation. Tailnet membership can also be broader than one
  person (shared/tagged nodes, ACL defaults). Dropping the token would turn any reachable tailnet/LAN node
  into a calendar writer. **Keep:** one-time explicit calendar choice, bearer token (only its SHA-256 is
  stored on the phone), TLS with pinned certificate, per-calendar scope, Stop and Unpair/revoke.
  Existing `DeviceLink.createPairing` also **rotates** the token on every call — callers must never call it
  speculatively (this drives the probe-first order in the UI, §3).

## 2. Discovery: `tailscale status --json`, MagicDNS, mDNS — none replaces pairing

- Desktop-side, `tailscale status --json` is the documented machine-readable peer list (`.Self`,
  `.Peer[]` with `DNSName`, `TailscaleIPs`, `Online`); MagicDNS gives each node a stable name and the
  100.64/10 IPv4 normally stays stable across networks.
  ([Tailscale CLI](https://tailscale.com/docs/reference/tailscale-cli),
  [MagicDNS](https://tailscale.com/docs/features/magicdns),
  [How Tailscale assigns IPs](https://tailscale.com/docs/concepts/ip-and-dns-addresses))
- But the current companion **cannot use it**: `validateDeviceUrl` in `integrations/mcp/src/device.js`
  accepts only a literal private IPv4 `baseUrl` (hostnames, including MagicDNS names, are rejected), and
  discovery would still not identify *which* peer runs Sunlith, nor supply token/certificate. Multiple
  phones/peers make silent auto-selection unsafe. Verdict: possible future desktop convenience
  (list online peers, let the user confirm the phone), never authorization, and it needs companion changes.
- Android NSD/mDNS (DNS-SD over multicast, `NsdManager`) is a **local-network** mechanism: same LAN only,
  needs multicast reception, and does not traverse the Tailscale VPN.
  ([Use network service discovery](https://developer.android.com/develop/connectivity/wifi/use-nsd),
  [NsdManager](https://developer.android.com/reference/android/net/nsd/NsdManager))
  Sunlith's server does not advertise an NSD service today, the desktop companion has no mDNS browser, and
  NSD would advertise presence to the whole LAN. Verdict: do not add for this fix. The phone already knows
  its own eligible addresses (`DeviceLinkNetwork.addresses`, Tailscale first), so the *phone* side needs no
  discovery at all — the user just sends the file to the computer they name in the Sharesheet.

## 3. Android sharing: FileProvider + Sharesheet + Taildrop (implemented)

- Android's documented secure pattern is exactly this: declare `FileProvider`, expose only a narrow
  subdirectory (here `cache-path "pairing-share/"` — broad `.`/`/` paths are called out as a leak risk),
  `getUriForFile()`, then `ACTION_SEND` + `EXTRA_STREAM` + `FLAG_GRANT_READ_URI_PERMISSION` in a chooser.
  `file://` URIs are not an option (exposed-URI crash/insecure on modern Android), and `cacheDir` files
  are shared through the narrow, unexported provider declared in the manifest.
  ([Setting up file sharing](https://developer.android.com/training/secure-file-sharing/setup-sharing),
  [FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider),
  [Improperly exposed directories](https://developer.android.com/privacy-and-security/risks/file-providers),
  [Send simple data](https://developer.android.com/training/sharing/send))
- Taildrop on Android is: *Share → Tailscale → tap the destination device*; files travel over the encrypted
  peer-to-peer tailnet path, **not to a Tailscale server**. Limits that the phone UI/docs must be honest
  about: Taildrop is alpha and must be enabled for the tailnet (“Send Files”), works only between devices
  signed in as the same user (not to other users' shared devices or tagged nodes), and the Tailscale entry
  may hide under “More” the first time.
  ([Taildrop](https://tailscale.com/docs/features/taildrop?tab=android))
- Implemented in `DeviceLinkScreen.kt` (`connectComputer()`):
  1. Probe `FileProvider.getUriForFile()` on a throwaway file in `cacheDir/pairing-share/` **before**
     `DeviceLink.createPairing()` — because create rotates the token, a failed probe must not orphan a pairing.
  2. Provider present → create pairing once, write a new random filename inside `cacheDir/pairing-share/`, share its
     `content://` URI (chooser title “Send to your computer”, then a hint appropriate to the selected Tailscale or local address). No document picker, no user-chosen save location.
  3. Provider absent → untouched old `CreateDocument` flow (pairing created only after a destination is
     chosen; cancelling creates nothing; write failure revokes, as before).
  4. “Share connection file again” re-shares the **same** cache file after a restart without rotating the
     token; the file is deleted on Unpair and every new pairing. It is never placed in
     saved state/logs. Residual risk to note: while paired, a raw-token copy lives in app-private cache
     (system may clear cache; app-private storage is not readable by other apps without root) — stricter
     lifecycle is enforced by the backend as described in §4.
  5. Sharing directly to the Tailscale app cannot be automated: Android requires the user to pick the
     Sharesheet target, and Sunlith must not hard-code another app's component or send the secret to an
     arbitrary handler silently. The one chooser tap is the platform's consent point.

## 4. Implemented address and sharing behavior

The backend returns typed address choices, sorted Tailscale VPN, other VPN, then local
Wi-Fi/Ethernet. The UI selects the first eligible address and retains a manual choice
when several are present. Cellular interfaces are excluded, including carrier CGNAT
addresses that happen to share Tailscale's address range.

A narrow, unexported FileProvider exposes only `cacheDir/pairing-share/` through temporary
read grants. Each pairing gets a new random filename, so a grant to an old URI cannot
read a replacement pairing. Re-sharing uses the existing credential without rotating it.
The backend accepts a cached export only when its token authorizes the current pairing;
re-pairing and revocation remove old exports. The pairing store itself contains only the
token hash, while this private, backup-excluded cache contains the exported credential.
Android can clear cache storage; a missing export cannot be reconstructed from the hash.

The UI probes the provider before creating credentials. If no provider is available, the
save-file fallback creates credentials only after the user chooses a destination. With the
provider, Connect computer creates the scoped pairing before opening Android's share list.
Cancelling that list keeps the pairing and exposes Share connection file again. No shared
file contents or credentials belong in logs or screenshots.

## 5. Native widget configuration

The widget now declares a configuration activity and
`widgetFeatures="reconfigurable|configuration_optional"`. Supported launchers offer
long-press > Edit widget or Reconfigure, with no extra control on the widget itself.
The optional configuration flag is supported from Android 12; older systems can open
configuration when adding the widget. Launcher wording and later reconfiguration support vary.

The activity starts with RESULT_CANCELED and the supplied widget ID, rejects invalid or
foreign IDs, and returns RESULT_OK with that ID after Done. Settings apply immediately
and are shared by all Sunlith widgets, which the screen explains. The activity updates
widgets before returning. Recreation retains the existing configuration route.

Sources: [widget configuration](https://developer.android.com/develop/ui/compose/glance/configuration),
[AppWidgetProviderInfo](https://developer.android.com/reference/android/appwidget/AppWidgetProviderInfo),
[FileProvider setup](https://developer.android.com/training/secure-file-sharing/setup-sharing),
[FileProvider security](https://developer.android.com/privacy-and-security/risks/file-providers).

## Sources and scope

Network and identity claims use the official Tailscale documentation linked above.
Android behavior uses the official platform documentation. The calendar authorization,
private export lifecycle, address filtering and literal-IPv4 companion restriction are
implementation details checked against this repository. No automatic Tailscale peer
discovery or silent file transfer is claimed.
