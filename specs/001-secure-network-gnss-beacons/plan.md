# Plan — 001: Secure offline member location network

## 1. Stack

| Area | Choice |
|---|---|
| Language / SDK | Kotlin 2.x, minSdk 26, target latest stable |
| UI | Jetpack Compose, Material 3, single-Activity + Navigation |
| DI | Hilt |
| Async | Coroutines + Flow throughout; no Rx |
| Persistence | Room (networks, peer snapshots) |
| Maps | osmdroid with bundled/offline MBTiles (no internet) |
| Crypto | Tink (AEAD) or lazodium (libsodium); Ed25519 identity via Tink signature |
| BLE GATT | Kable (coroutines/Flow) — used by both transport/bluetooth and transport/lora node link |
| USB serial | usb-serial-for-android (mik3y) — wired LoRa node link (CH340/CP210x/FTDI/CDC-ACM) |
| Build | Gradle KTS, version catalog, ktlint + detekt |

## 2. Module layout

```
app/                      # Compose app, navigation, permissions wiring
core/model/               # pure Kotlin: NetworkProfile, BeaconFrame, PeerState
core/crypto/              # pure Kotlin: KDF, AEAD, identity keys, frame codec
core/session/             # pure Kotlin: beacon scheduler, peer tracker, staleness,
                          # edge manager, relay (seen-cache + ttl)
transport/api/            # pure Kotlin: Transport interface + events
transport/wifidirect/     # WifiP2P implementation
transport/bluetooth/      # BLE GATT server/client (or RFCOMM) implementation
transport/lora/           # BLE serial link to member's own LoRa node (byte pipe)
transport/share/          # INTERNET carrier: Viber share intent + share-target inbox (US-9)
feature/networks/         # create/join/list networks UI
feature/map/              # map + member list screens
```

Dependency rule (enforced in CI): `core/*` and `transport/api` have **zero** Android
dependencies. Only `transport/*` impls, `feature/*`, `app` touch Android APIs.

## 3. Key designs

### 3.1 Transport strategy (P3)

```kotlin
interface Transport {
    val id: TransportId                       // WIFI_DIRECT, BLUETOOTH, LORA
    fun start(profile: NetworkSessionConfig): Flow<TransportEvent>
    suspend fun stop()
    suspend fun send(frame: EncryptedFrame): SendResult
}
sealed interface TransportEvent {
    data class FrameReceived(val frame: EncryptedFrame) : TransportEvent
    data class PeerRadioVisible(val radioPeerId: String) : TransportEvent
    data class StateChanged(val state: RadioState) : TransportEvent
}
```

- `TransportRegistry` binds `TransportId → Transport`; a network session runs a
  **set** of transports concurrently (per-edge mesh, US-3): `EdgeManager` keeps
  `edge(memberA,memberB) → TransportId` and picks the outgoing transport per
  destination; unknown destinations = flood via all active transports.
- Each transport maps its native addressing to an opaque `radioPeerId`; membership trust
  never comes from the radio layer — it comes from AEAD auth under network keys.

### 3.2 Keys & frames (P2)

- Join string `name#secret#vtag` (vtag = HKDF(secret,"verify")[0..4]). Derive:
  - `networkId = HKDF(secret, info="net-id")` first 8 bytes (public on wire, routes to
    right network)
  - `trafficKey = HKDF(secret, info="aead")`
  - `memberId = Ed25519_public_key` generated once per device per network
- Wire frame: `[magic|ver|networkId(8)|senderId(32)|seq(u32)|sentAt(u64)|len|ciphertext]`,
  ciphertext = AEAD(payload, AAD = header). Replay window = last 128 seqs per sender +
  ±10 min clock sanity. Relay (FR-3.3): header carries `ttl(u8)` (init 3, decremented per
  hop); seen-cache (senderId,seq) suppresses duplicates; split horizon = never resend on
  the ingress transport.
- Payload: CBOR `{pos?: [latE7, lonE7, accCm, altCm], fixAgeS?, battery?}`.
- Management frames (US-8, cleartext + Ed25519 signature): `HELLO`, `JOIN_REQ`,
  `JOIN_ACK`, `JOIN_ACCEPT`, `JOIN_REJECT` — same header, `type` in payload, senderId
  doubles as signing key id. Secret travels only inside ECIES(X25519 eph,
  XChaCha20-Poly1305) in `PRIVATE` ACCEPT; SAS = base32(transcript SHA-256)[0..30],
  6-char groups, compared by users on both screens. See `handshake.md`.

### 3.3 GNSS & scheduling (US-4/5)

- `GnssSource` interface returning `Flow<GnssFix>`; default impl uses
  LocationManager GPS provider with `setMinUpdateIntervalMillis`. Foreground service hosts
  it while any session is active (persistent notification, P7).
- `BeaconScheduler`: emits send request per new fix; timer fallback keep-alive every 60 s.

### 3.4 Peer tracking (US-6)

- Pure-Kotlin `PeerTracker(networkId)`: consumes verified frames → `StateFlow<Map<memberId,
  PeerState>>`; implements FR-6.1/6.3 staleness timers. Room persists snapshots for
  cold-start display only.

### 3.5 Per-edge transport UX (US-3)

Network list shows `name · policy · members · links-up` (no single protocol — edges may
differ). Network detail hosts the edge matrix: member chip -> edge card -> transport
selector. Map draws edges between located peers, each labeled with its transport
(FR-3.1). A session runs several transports concurrently (§3.1).

### 3.7 LoRa node link (US-7)

- `LoraNodeLink` (interface, byte pipe) has two implementations inside `transport/lora`:
  - `BleNodeLink` — Nordic-UART-style GATT (write-without-response TX, notify RX), via
    Kable
  - `UsbSerialNodeLink` — usb-serial-for-android (CH340/CP210x/FTDI/CDC-ACM), 115200 8N1
    default, USB host permission via intent filter; wired = no radio duty cycle, no
    charging conflict handled by user
- Both feed already-encrypted app frames verbatim (opaque pipe, FR-7.2). Node firmware
  (Meshtastic / RadioLib-based, pre-provisioned) owns the radio; Meshtastic's own BLE
  protobuf API is a possible v2 alternative link instead of raw NUS.
- Link type + node selection in the node picker (BLE scan or USB device list); last
  node remembered per network profile. Auto-reconnect with backoff (FR-7.4); bounded
  TX queue (FR-7.5).
- MTU: BLE requests ≥185 B so one beacon fits one write, else 2-byte length-prefix
  chunking; USB serial always length-prefix chunked (stream-oriented).

### 3.10 Internet carrier via messenger share (US-9)

- `ShareTransport` (transport/share): send() composes `fmiw1:<base64url(wireFrame)>`
  and opens the Viber share sheet (SendResult.HandedToUser). Receive: app-wide
  ACTION_SEND share target decodes envelopes into `SessionManager.acceptExternalWire`.
- Envelope = the same AEAD wire frame -> carriers (Viber/Telegram/SMS) are untrusted
  transports; no INTERNET permission, no server, no bot tokens.

### 3.8 Permissions

ACCESS_FINE_LOCATION (+BG not needed), NEARBY_WIFI_DEVICES, BLUETOOTH_SCAN/CONNECT,
FOREGROUND_SERVICE(+LOCATION subtype). Runtime prompts flow from feature modules via a
`PermissionGate` abstraction.

### 3.9 Edge transport changes (FR-3.6)

- Receiving happens on ALL active transports; `EdgeManager` only steers the SEND
  preference per edge. Dedup by (senderId,seq) makes multi-path delivery harmless.
- Change procedure: humans agree out-of-band (cell call / in person) → both select the
  new transport for the edge → devices attempt association → first AEAD frame received
  confirms; UI shows "waiting for peer" until then.
- Failover: on edge-down detection (peer silent ≥3 windows) both devices auto-attempt
  their other provisioned transports (backoff, battery-cost order). Edge re-homes to
  the first transport delivering a peer AEAD frame. No HELLO/management frames —
  periodic beacons are the discovery; dedup makes multi-path harmless.
- HELLO is used only for network discovery (US-8), never for edge re-homing.

## 4. Testing

- JVM unit: crypto vectors, codec round-trip, replay window, PeerTracker staleness,
  scheduler logic (constitution P6).
- Instrumented smoke: WiFi Direct discovery between two devices; BLE loopback.
- Manual matrix: Pixel-class device × {WiFi Direct, Bluetooth}; two phones + two LoRa
  nodes for the LoRa path (node firmware assumed pre-provisioned).

## 5. Milestones

1. M1 — core/crypto + codec + session, all JVM-tested.
2. M2 — Bluetooth strategy end-to-end beacons between two phones (simplest radio).
3. M3 — GNSS service + map screen (offline tiles).
4. M4 — WiFi Direct strategy.
5. M5 — LoRa-via-BLE strategy against real nodes + docs; battery hardening; release build.
6. M6 — Mesh: concurrent transports per session, EdgeManager, TTL relay + seen-cache,
   per-edge matrix UI (US-3 target state).
