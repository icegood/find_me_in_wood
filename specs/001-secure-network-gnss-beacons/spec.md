# Spec — 001: Secure offline member location network

**Status:** draft → review
**Input:** user request "create predefined named secure network between phones; each phone
reads GNSS; members exchange beacons over WiFi Direct / Bluetooth / LoRa so everyone can
see where other members are".

## 1. Overview

Android app ("find me in wood") letting a group of phones, with no internet and no
infrastructure, form a predefined named secure network and keep track of every member's
GNSS position on a map.

## 2. User stories

### US-1 — Create a named secure network
**As** a group leader **I want** to create a network by choosing a name and having the app
generate a shared secret **so that** only people I give (name + secret) to can join.

- FR-1.1 Creating a network requires exactly two inputs: name (1–64 chars) and a
  passphrase or generated key. Output of creation is a join string
  `name#secret#vtag` (vtag = HKDF(secret,"verify")[0..4]) to share out-of-band; the
  name itself is never transmitted and exists only as a local profile label.
- FR-1.2 The device stores the network locally; multiple networks may exist side by side.
- FR-1.3 All per-network key material is derived from the secret via KDF; the raw secret
  is not stored after derivation.

**Acceptance:** Given a fresh install, when I create network "hunt-2026" with a generated
key, then the network appears in my list with its active protocol shown, and a join code
can be copied/exported.

### US-2 — Join an existing named network
**As** a group member **I want** to enter name + secret **so that** I become a full member.

- FR-2.1 Joining = entering the same join string; wrong secret fails fast offline
  (vtag mismatch) before any radio traffic.
- FR-2.2 A member chooses a display nickname (not necessarily unique; identity is the
  device's own keypair).
- FR-2.3 Joining has no online handshake: the first validly decrypted frame is the key
  confirmation (PSK model). Generated secrets MUST be ≥128-bit; user passphrases MUST
  pass an entropy check (≥6 words or ≥16 chars) or creation is refused — weak secrets
  would enable offline dictionary attacks on captured frames.

**Acceptance:** Given network "hunt-2026" exists on phone A, when phone B joins with the
correct pair, both show the same networkId; with a wrong secret B gets an explicit error
and sends nothing over the air.

### US-3 — Per-edge transport selection (mesh)
**As** members of one network **I want** each pair of us to use the transport that fits
our physical situation — e.g. far member A↔B and A↔C over LoRa, close B↔C over
Bluetooth — **so that** everyone stays connected with the least radio cost.

- FR-3.1 The network is a connectivity graph. Each edge (pair of members) has a
  transport strategy chosen by those members (default suggestion by range: LoRa >1 km,
  BT/WiFi-Direct <100 m). Edges are visible in the network detail as a member matrix.
- FR-3.2 A member MAY run several transports concurrently for one network (e.g.
  Bluetooth + a LoRa node link). Session state aggregates all edges.
- FR-3.3 Frames relay across edges: on receiving a valid (AEAD-opened) beacon not seen
  before, a member rebroadcasts it on all *other* edges (split horizon) while
  TTL > 0 (default TTL = 3). Seen-cache keyed by (senderId, seq); relay never
  rebroadcasts frames that fail authentication.
- FR-3.4 A peer's position may arrive via a relay path; the UI may show "via B" when
  the direct edge is down. Peer staleness rules (FR-6.3) apply per member regardless
  of path.
- FR-3.5 Transport choice per edge is manual in v1 (members agree, e.g. "A and B both
  switch LoRa on"); automatic selection by measured RTT/RSSI is a v2 goal.
- FR-3.6 Edge transport changes are agreed between the two members out-of-band first
  (cell call, in person — "switch us to LoRa"), then each side selects the new transport
  for that edge in the UI. Both devices attempt to bring it up; the first AEAD frame
  received on it confirms the switch. Until then the edge shows "waiting for peer".
- FR-3.6a Failover: if the selected edge transport dies (peer silent ≥ 3 keep-alive
  windows) and no coordination channel exists, both devices automatically attempt their
  other provisioned transports for that edge (retry with backoff). The edge re-homes to
  the first transport on which a peer AEAD frame arrives; no HELLO or management frame
  is involved — periodic beacons themselves are the discovery. Attempt order: last
  working transport first, then remaining by battery cost (BT/WiFi-Direct before LoRa).
  A transport is only attempted if provisioned for this network (e.g. node ready).

**Acceptance:** A (far, LoRa node), B (LoRa node + Bluetooth), C (near B, Bluetooth):
C sees A's position via B's relay within two beacon intervals; B sees both directly;
switching B's Bluetooth off leaves A↔B intact and marks A "via LoRa" on C after TTL
expiry.

### US-4 — Read GNSS fix
**As** a member **I want** the app to read my GNSS position continuously while sharing.

- FR-4.1 Use platform GNSS (LocationManager/GPS provider). Fix includes lat, lon,
    horizontal accuracy, altitude, timestamp.
- FR-4.2 Fix interval default 30 s, adjustable 5–300 s; last-known fix used immediately
  at start.
- FR-4.3 No-fix state is first-class: beacon carries `position: null` + fix age, so peers
  know I'm alive but unlocated.

### US-5 — Beacon exchange over selected protocol
**As** a member **I want** my position broadcast periodically to network peers.

- FR-5.1 Beacon frame: `{version, networkId, senderId, seq, ttl, sentAt, position?,
  battery?}` (ttl for relay, FR-3.3)
  encrypted+authenticated (AEAD) under keys derived from the network secret.
- FR-5.2 Cadence: send on every new fix (bounded by fix interval), plus keep-alive beacon
  without position at least once per 60 s while sharing is on.
- FR-5.3 Sharing toggle per network pauses position field but keep-alives continue unless
  the whole network session is stopped.

### US-6 — See where other members are
**As** any member **I want** received beacons plotted so I can navigate to / find others.

- FR-6.1 Peer table per network: memberId → {nickname, lastPosition, lastSeen, accuracy}.
  Updated from verified beacons only.
- FR-6.2 Map screen (offline tiles) shows me + all located peers; peers without a recent
  fix appear as "unlocated, seen Xm ago".
- FR-6.3 Stale rule: peer marked inactive after 3 × keep-alive interval (~3 min) missing;
  entry purged after 24 h.
- FR-6.4 List view alternative for mapless use (distance & bearing to each peer).
- FR-6.5 Map tile source selectable in settings (OSM / Google / satellite style). Tiles
  are cached locally so the default experience stays offline (P1); third-party
  attribution is displayed on the map whenever a source requires it.

### US-7 — Attach a local LoRa node over BLE
**As** a member **I want** the app to talk to my own LoRa device over Bluetooth LE
**so that** beacons can ride on LoRa when the network's selected protocol is LoRa.

- FR-7.1 Every member carries their own LoRa node; the app connects to it via BLE and
  uses it as an opaque byte pipe: send frame in, received frames out.
- FR-7.2 The app has NO knowledge of how nodes reach each other over the air (modulation,
  meshing, addressing inside the LoRa network is entirely the node's business). Frames are
  handed to / read from the node verbatim after app-level crypto.
- FR-7.3 Node discovery: scan for compatible BLE nodes by GATT service UUID (generic
  Nordic-UART-style serial service) or user-pinned device; remember last node per network.
- FR-7.4 Node link state (disconnected / connecting / ready) is visible on the network
  screen; auto-reconnect while a LoRa session is active.
- FR-7.5 Backpressure: if the node link is down, outgoing beacons queue up to N=5 newest
  per peer cycle, then drop oldest; nothing is ever sent unencrypted to fill bandwidth.
- FR-7.6 Node link types: BLE (Nordic-UART-style serial) and wired USB serial
  (usb-serial-for-android drivers: CH340/CP210x/FTDI/CDC-ACM) — identical byte-pipe
  semantics, selectable in the node picker. Both are thin transports; LoRa specifics
  stay in node firmware (Meshtastic/RadioLib class).

**Acceptance:** With "hunt-2026@LoRa" active and my node paired, my beacon reaches other
members' phones through their nodes; unplugging my node shows link state "disconnected",
keeps GNSS fixes queued briefly, and reconnects automatically when the node returns.

### US-8 — Discover networks & owner, P2P join handshake
**As** a newcomer with no join code **I want** to see which networks exist around me and
who owns them, and join via an on-radio handshake **so that** no server or out-of-band
channel is required.

- FR-8.1 Members periodically emit signed cleartext `HELLO` management frames
  {netId, name, ownerMemberId, ownerNick, activeTransports} (rate-limited; on LoRa
  owner only, one HELLO per active transport). `CODE`-policy networks omit the name
  (hidden). Member count is NEVER advertised — it is tracked only inside the network
  (owner's and members' peer tables).
- FR-8.2 Discovery screen lists heard networks with name, owner nickname and join
  policy — never a member count (FR-8.1). Network "owner" = creator's memberId,
  informational, advertised in HELLO.
- FR-8.3 Join policies: `OPEN` (anyone joins instantly; secret delivered in clear
  ACCEPT; UI warns it is readable-by-anyone), `PRIVATE` (chip label "PRIVATE · ask
  owner": owner confirms; secret via ECIES to joiner's ephemeral X25519 key; 6-digit
  SAS compared on both screens), `CODE` (hidden, no handshake — US-2). Policy set at
  creation.
- FR-8.4 Handshake frames JOIN_REQ/JOIN_ACK/JOIN_ACCEPT/JOIN_REJECT carry nonces and
  are transcript-signed by the owner; replay is rejected by nonce echo. SAS comparison
  requires an auxiliary human channel (co-presence or voice); when the owner cannot
  verify, the only options are Reject or "Accept unverified" — the member is stored
  with an `unverified` flag shown in the member list, because without SAS a MITM could
  have captured the secret.
- FR-8.5 After ACCEPT the joiner derives keys locally and proves membership by first
  AEAD beacon; only then do others display them.
- FR-8.6 Management frames never contain the secret in a form readable by non-participants
  (clear only under `OPEN`, which the UI flags as readable-by-anyone).

**Acceptance:** With "hunt-2026@PRIVATE" active on the owner's phone, a stranger's
discovery screen shows "hunt-2026 · owner Misha" (no member count); after Accept + matching SAS
the stranger appears on everyone's member list within one beacon interval.

### US-9 — Internet carrier via messenger share (Viber)
**As** a member **I want** beacons to reach peers through Viber when internet exists
**so that** the group stays connected across distances without any custom server.

- FR-9.1 Carrier = Android share intent, preferred target Viber (`com.viber.voip`),
  generic chooser fallback (Telegram, SMS, any text app); the app
  composes the text envelope `fmiw1:<base64url(wireFrame)>` — the same AEAD-encrypted
  frame as on radio. Viber (and any other messenger) is an untrusted carrier; it cannot
  read positions. No INTERNET permission is required (NFR-2 intact).
- FR-9.2 Sending is user-in-loop: "Share position" opens the share sheet with the
  envelope pre-filled; optional per-network auto mode copies the newest envelope to the
  clipboard on every fix. No silent background sending (messenger APIs don't allow it).
- FR-9.3 Receiving: the app registers as a text share target (ACTION_SEND). The member
  shares the Viber message into find_me_in_wood; the app strips the envelope, verifies
  the frame (AEAD + replay), and feeds the tracker exactly like radio frames. Invalid
  frames are dropped with the auth-fail counter.
- FR-9.4 The channel appears as transport `INTERNET` in the edge UI and diagnostics;
  it is never auto-selected (FR-3.6 human agreement still applies).

**Acceptance:** A (internet, Viber) and B (no internet, in the field): A shares an
envelope to B's contact; B shares it into the app; B's map shows A within seconds and
B's keep-alive envelope can travel back the same way.

## 3. Non-functional requirements

- NFR-1 minSdk 26, target latest stable Android; Kotlin only.
- NFR-2 No internet permission requested by default builds.
- NFR-3 Frame crypto via AEAD (XChaCha20-Poly1305 or AES-256-GCM); Ed25519 device identity
  signing beacon headers (replay protection: seq + timestamp window).
- NFR-4 Core modules build & test on JVM (`./gradlew :core:test`) with no emulator.
- NFR-5 LoRa strategy = local node as opaque byte pipe over BLE or wired USB serial
  (US-7); works with any node exposing a generic BLE serial service or USB CDC/UART.
  Reference implementations: Meshtastic Android app (BLE), usb-serial-for-android +
  USBRFMApp-style Arduino pairing (USB).

## 4. Out of scope (v1)

- Chat/messages beyond beacons; iOS; automatic per-edge transport selection (v2, FR-3.5);
  ownership transfer; bridging to foreign meshes; maps requiring internet; background
  auto-restart after reboot. Mesh relay itself is IN scope (FR-3.3, delivered M6).

## 5. Risks

| Risk | Mitigation |
|---|---|
| WiFi Direct flaky on OEM ROMs | Bluetooth as first-class fallback; strategy isolation keeps failures contained |
| LoRa bandwidth tiny (~beacon ≈ 60–100 B) | Compact binary frame codec, position rounded to ~2 m grid |
| Heterogeneous LoRa nodes (firmware differences) | App treats node as dumb byte pipe over standard BLE UART; only framing at app boundary |
| Replay/jamming | seq+timestamp window, drop-on-auth-fail counters surfaced in UI |
