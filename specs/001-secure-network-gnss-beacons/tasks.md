# Tasks — 001: Secure offline member location network

Order matters inside phases; phases 1–2 are prerequisites for all radio work.

## Phase 1 — Skeleton & core (pure Kotlin, M1)

- [ ] T1.1 Gradle KTS project, version catalog, modules per plan §2; ktlint+detekt in CI; dependency rule check (core/* must not see `android.*`)
- [ ] T1.2 core/model: `NetworkProfile`, `TransportId`, `GnssFix`, `BeaconPayload`, `PeerState`, CBOR payload codec
- [ ] T1.3 core/crypto: HKDF derivation (`networkId`, `trafficKey`), device Ed25519 identity per network, AEAD seal/open, test vectors
- [ ] T1.4 Wire frame encode/decode + replay window (128 seqs, ±10 min); fuzz/property tests
- [ ] T1.5 core/session: `PeerTracker` with FR-6.3 staleness (3 min inactive / 24 h purge), unit-tested clock injection
- [ ] T1.6 core/session: `BeaconScheduler` (send on fix, keep-alive 60 s), JVM-tested

## Phase 2 — Transport API + first radio (M2)

- [ ] T2.1 transport/api: `Transport` interface, `TransportEvent`, `EncryptedFrame`, `SendResult`; fake transport for tests
- [ ] T2.2 Session manager wiring profile→strategy→scheduler→tracker (pure Kotlin orchestrator)
- [ ] T2.3 transport/bluetooth: BLE GATT dual-role per bt-transport.wsd (custom service f11d0a01-…, CHAR_WRITE/CHAR_NOTIFY, MTU 247, u16 length-prefix chunking)
- [ ] T2.4 End-to-end instrumented smoke: two devices exchange beacons over BLE, tracker updates both sides
- [ ] T2.5 Android foreground service hosting an active session (notification per P7)
- [ ] T2.6 Management frames: HELLO emitter (rate-limited, policy-aware) + signed parser; discovery list data source (US-8, JVM-tested)
- [ ] T2.7 Join handshake state machine: JOIN_REQ/ACK/ACCEPT(+REJECT), ECIES secret delivery, SAS derivation + nonce replay guard (JVM-tested with fake transport)
- [ ] T2.8 UI: join hub with discovery list (S4 rework, done in design), owner accept dialog with SAS compare, waiting/SAS screen, policy selector in create flow

## Phase 3 — GNSS & UI (M3)

- [ ] T3.1 GnssSource (LocationManager GPS) → Flow<GnssFix>, last-known fix on start, no-fix beacon path (FR-4.3)
- [ ] T3.2 feature/networks: create network (generate secret), join (code input + discovered list), list with `name · policy · links`; join-policy
      selector in create flow (FR-8.3)
- [ ] T3.3 feature/map: osmdroid screen with self+peers, unlocated-peer badge, member list view w/ distance+bearing (FR-6.4)
- [ ] T3.4 Permission flows via PermissionGate; graceful degradation when denied
- [ ] T3.5 Room persistence of networks + peer snapshot restore on app restart

## Phase 4 — WiFi Direct strategy (M4)

- [ ] T4.1 transport/wifidirect: per wifidirect.wsd (group negotiation, ServerSocket(8765) both sides, u32 length-prefix TCP framing)
- [ ] T4.2 Instrumented two-device smoke over WiFi Direct; verify per-edge switch keeps peer data (FR-3.2)

## Phase 5 — LoRa-via-BLE strategy & hardening (M5)

- [ ] T5.1 transport/lora: `LoraNodeLink` interface + `BleNodeLink` per lora-node.wsd (Kable, NUS 6e400001-…, 0x464D-magic + u16 length-prefix stream framing)
- [ ] T5.1b `UsbSerialNodeLink` via usb-serial-for-android (device list, 115200 8N1, stream chunking); same byte-pipe tests
- [ ] T5.2 LoraTransport strategy: opaque byte-pipe semantics (FR-7.2), auto-reconnect w/ backoff (FR-7.4), bounded TX queue N=5 (FR-7.5); JVM-tested with fake node link
- [ ] T5.3 Node picker UI: link-type selector (BLE|USB), discovered/USB device list, pin device, per-network last-node memory; link state badge on network screen
- [ ] T5.4 Two-phone + two-node field smoke: beacons flow phone→node→(air)→node→phone both directions
- [ ] T5.5 Drop/auth-fail counters surfaced in network detail screen
- [ ] T5.6 Battery validation vs P7 budget (overnight drain log)
- [ ] T5.7 Release build: minify, no INTERNET permission audit (NFR-2), README quickstart incl. node requirements

## Phase 7 — Internet carrier via Viber share (M7, US-9)

- [ ] T7.1 transport/share: ShareTransport (envelope fmiw1:base64url, Viber share intent, HandedToUser) + envelope parse tests
- [ ] T7.2 ShareInboxActivity share-target -> SessionManager.acceptExternalWire; auth-fail counter path
- [ ] T7.3 UI: "Share position" action on network screen + optional auto-copy-to-clipboard setting; diagnostics count carrier frames

## Phase 6 — Mesh: per-edge transports + relay (M6, US-3)

- [ ] T6.1 Frame header ttl(u8) + seen-cache (senderId,seq) + split-horizon rebroadcast in session core (JVM-tested flood scenarios: loop, duplicate, TTL expiry)
- [ ] T6.2 Concurrent multi-transport session (TransportManager): run N transports, EdgeManager edge table `edge(a,b)→TransportId`, flood for unknown destinations
- [ ] T6.3 "via" path tracking in PeerState (last-hop member, edge transport) + UI badge on member card
- [ ] T6.4 Network detail: per-edge matrix (member × member → transport), edit edge transport
- [ ] T6.5 Three-device field test: A–B LoRa, B–C Bluetooth, C receives A via relay; battery impact report (P7)
- [ ] T6.6 Edge transport change + failover: out-of-band agreement flow, local selection, "waiting for peer" state; edge-down detection (3 windows) -> auto-attempt other provisioned transports w/ backoff, re-home on first peer AEAD frame (JVM-tested + two-device smoke)
