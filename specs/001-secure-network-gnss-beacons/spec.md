# Spec — 001: Secure offline member location network

**Status:** current (rewritten 2026-10-13 from the decisions taken while building it; every
requirement below is checked against the code, see `review.md`).

## 1. Overview

Android app ("find me in wood") letting a group of phones, with no internet and no
infrastructure, form a secure network and keep track of every member's position on a map.
Phones talk directly over Bluetooth LE, Wi-Fi Direct, an attached LoRa node or SMS; every
frame is encrypted and authenticated with the network's key.

## 2. Concepts

- **Network** — a name (local label) + a password. All keys derive from the password;
  removing a member re-salts the traffic key (FR-8.9).
- **Member** — a phone in a network. Its **member id** is the SHA-256 of its device
  identity public key, the same key that signs its beacons, in every network.
- **Master** — the phone that created the network. Only the master lets people in and
  removes them. The network keeps working without the master nearby.
- **Member list** — who is in the network, signed by the master.
- **Member card** — what a member says about itself (name, links), signed by that member.
- **Link** — the radio two members agreed to use between them (LNP/3, FR-3.5–3.10).
- **Discovery** — being findable by new people. Off unless the master turns it on.

## 3. Requirements

### US-1 — Create a network
- FR-1.1 Creating takes a name (1–64 chars) and a password: generated (26 chars, ≥128 bit)
  or typed (≥16 chars or ≥6 words, otherwise refused). The name is a local label only.
- FR-1.2 Network id, traffic key and verify tag derive from the password (HKDF). Several
  networks may be stored side by side, sealed with the Android Keystore.
- FR-1.3 The creating phone is the network's master (stored with the profile).
- FR-1.4 A new network starts at once when no other network is running (a running one is
  never switched away), and shows the invite (password, Copy, Send via app…).

### US-2 — Join a network
- FR-2.1 There is one join policy: **ask the master** (FR-2.3) or **enter the password**
  (FR-2.2). No other policies exist.
- FR-2.2 Join by password: from Networks → "Join by password" (anywhere) or from a network
  listed in Discover (the password is then checked against the heard network id before
  anything is sent: wrong password = explicit error).
- FR-2.3 Ask the master: the joiner connects to a phone advertising the network and sends a
  signed JOIN_REQ with an ephemeral key; only the master answers (other members ignore
  it). Accept sends the password sealed to the joiner's ephemeral key (ECDH + AEAD);
  Reject says so. Both phones show the same 6-digit code; the joined network shows the
  code until "The codes matched" is tapped.
- FR-2.4 An accept counts only when its password derives exactly the network asked for.
- FR-2.5 After joining by asking, the joiner starts the network with the master recorded;
  after joining by password it learns the master from the first valid member list.
- FR-2.6 Join by QR code: the master's Invite shows the password as a QR code holding
  `fmiw://join?n=<name>&p=<password>`. The joiner taps "Scan QR" in any password dialog,
  or scans it with the phone's own camera app, which opens this app on "Join by password"
  with name and password filled in; it then joins as FR-2.2.

### US-3 — Links between members (mesh)
- FR-3.1 Links are pairwise: every pair of members is a point-to-point link of its own.
  The pair alone decides which transports it may use, which one it uses and, for Wi-Fi
  Direct, which of the two hosts; nothing another pair decided changes it. A medium shared
  by several members (one Wi-Fi Direct group) exists only when each of them chose it
  (FR-3.5d). A member may run several radios at once (Bluetooth, Wi-Fi Direct, LoRa node,
  SMS), each serving the pairs that agreed on it.
- FR-3.2 Every phone shows every supported link for each member: usable ones, and greyed
  ones with the reason and a fix (Turn on, Set number, Allow). Satellite is a note: SMS
  uses it when the carrier does. Only usable links are offered in the handshake.
- FR-3.3 Frames relay: a valid frame not seen before is re-sent while TTL > 0 (start 3) to
  every other member over that pair's own link — the radio it came in on included, to
  every link but the one it came from (a Wi-Fi Direct host or a phone with several
  Bluetooth links is the only way between the members around it; LoRa is not relayed back
  onto its own air). A relay never uses a link the next pair did not agree on. Duplicates
  and frames failing authentication are never relayed.
- FR-3.4 A member heard only through others is shown "via others".
- FR-3.5 Links are negotiated in-band by LNP/3: one handshake per pair agrees the *set* of
  usable transports; within it the link switches at any time without a new handshake.
  There is no fallback — nothing switches by itself.
- FR-3.5a Routing follows each pair's decisions: a member's frames reach a peer only over
  transports that pair agreed on — on point-to-point links (a Wi-Fi Direct socket, a
  Bluetooth connection) per peer; on a broadcast medium (LoRa air) while some pair on it
  agreed. Every agreed transport of the pair carries them, so the pair keeps reaching each
  other while any of them does. While the pair has not decided yet (offers unknown, or a
  common transport undecided) frames go on every radio, so the handshake can get through.
  Management frames (discovery, joining) are not limited.
- FR-3.5b Wi-Fi Direct roles are per pair, for every member this phone can pair with on it
  (agreed, or not decided yet): of the two, the lower member id hosts, unless
  the pair picked the other way round in the link menu (either member may; the newer pick
  wins, as selections do). Computed from the pair alone, kept while the two are out of
  touch, so they rejoin the same group. Separate pairs run separate groups at the same
  time (A–B and C–D are two groups). A phone hosts at most one group and cannot be in two
  (Android): all its pairs that make it host share its one group (each still a pair of
  its own, FR-3.5d), and when its pairs need it in several groups (join two hosts, or join
  one and host for another) it switches between them by itself: it serves each such pair
  in turn, long enough to exchange what is pending (at least one beacon each way), and
  goes to a pair at once when that pair has something urgent (a handshake step, a chat
  message, a find request). Nothing waits for the user. The member row says when the
  pair's link is shared in time ("Wi-Fi Direct, in turn with 2 others").
- FR-3.5d A host's group is shared only by the pairs that each chose it: every member in it
  joined by its own pair agreement with the host (and the host's pick, FR-3.5b), and the
  host closes the link of any member whose pair turned Wi-Fi Direct down (FR-8.7). Members
  in one group reach each other only through the host's relay over their own pair links
  (FR-3.3, shown "via others"), never directly.
- FR-3.5c Two phones that both allowed Wi-Fi Direct connect over it with only Wi-Fi on
  (Bluetooth off), and on Android 13+ with Location off (the app asserts it never locates
  by Wi-Fi). Up to Android 12 Location is required: Wi-Fi Direct is then shown unavailable
  with a "Turn on" fix. The pair reconnects by itself within 20 s of whatever dropped it (a pair
  shared in time, FR-3.5b: within 20 s of its turn)
  (Wi-Fi off and on, a group lost, an app restart), also with a screen off: requests are
  retried every 5 s and given up after 15 s, and every socket carries a heartbeat every 5 s,
  so one silent for 15 s is closed instead of being counted as a link. The network the user
  left active comes back by itself after an app update, a force stop or a process death;
  only the user's stop ends it. A member's row says "Connected via X" only while frames really come in over X;
  otherwise it names the radio that carries them, or that the member is heard only through
  others.
- FR-3.6 Offers: each member lists its usable transports in its signed, encrypted beacon
  (`link.offers`: BLUETOOTH | WIFI_DIRECT | LORA | SMS + address, e.g. SMS number). An
  offer exists only while usable (radio on, node ready, SMS allowed + own number set).
  Link control rides on beacons when something changed or is pending, else every 5th.
- FR-3.6a Radios switched in the app or outside it (quick settings, airplane mode) are
  followed: the offer goes within seconds, the radio reports off and stops all requests
  (no Wi-Fi Direct joins while P2P is off), and when it is back on it looks for its links
  at once. Decisions hold meanwhile, so the agreed transport returns without a new one.
  The link log records every switch (`radio_power`).
- FR-3.7 Handshake, per transport: for each transport both offer (order BLUETOOTH,
  WIFI_DIRECT, LORA, SMS) each user decides on their own phone — Allow / No, then a switch
  to connect or disconnect it at any time. A transport is agreed only while **both** users
  allow it. Decisions are kept per network across app restarts and updates, so a known pair
  reconnects without anyone tapping again. Each phone acks the list it allows, bound to both addresses by hash, and echoes
  the peer's ack so both know the step is done. A decision holds while the radio is off; a
  new transport or address needs a new one. Nobody is texted without having agreed.
- FR-3.7a No waiting: offers go out as soon as a radio link comes up; every handshake step
  and every selection is sent at once in an extra beacon (≤1/s, deferred, never dropped).
- FR-3.8 Selection: both start on the cheapest agreed transport. Either member may select
  another agreed one; it applies at once and the peer follows from the next beacon without
  answering (higher epoch wins; equal epochs: lower member id). A quiet selected link only
  produces a suggestion ("Bluetooth quiet — use Wi-Fi Direct?").
- FR-3.9 SMS link: one 47-byte encrypted position SMS (`FMIW1:` + base64url) per peer per
  30 min, at most 10 SMS per hour. No separate distress mode.
- FR-3.10 Relays and the messenger carrier never count as hearing a member directly.

### US-4 — Position
- FR-4.1 Platform GNSS fixes (lat, lon, accuracy, altitude, time); last known fix at start.
- FR-4.2 Beacon interval default 60 s, adjustable 5–300 s.
- FR-4.3 No fix = beacon without position (peers see "unlocated, last seen …").
- FR-4.4 **Pause sharing**: one tap on the active network stops sending my position;
  beacons continue without it, so I stay reachable and still see others.
- FR-4.5 **Faked satellites**: electronic warfare broadcasts fake satellite signals, and the
  phone then reports a confident position far away. A satellite position is dropped when it
  disagrees with a Wi-Fi/cell position of the last 10 min by more than both errors allow
  (at least 2 km) or, without one, lies further from the last trusted position than 360 km/h
  allows; mock-location apps count too. Meanwhile Wi-Fi/cell positions are used and sent,
  the map says "GPS faked nearby", and the first satellite position that passes ends it.

### US-5 — Beacons
- FR-5.1 Beacon: header {networkId, senderId, seq, ttl, sentAt} + encrypted payload
  {position?, battery?, link?, book fingerprint, signature, public key}.
- FR-5.2 Sent on every new fix (bounded by the interval) plus a keep-alive at least every
  interval.
- FR-5.3 The signature covers seq + time + payload; replays (seq/time window) are dropped;
  a sender's key is pinned on first sight.

### US-6 — Seeing members
- FR-6.1 Peer table from verified beacons only: name, position, last seen, link.
- FR-6.2 Map (MapLibre) shows me + located members (named; greyed while searching for
  someone else); remembers the last view; scale bar; north stays up. The map chooser lists
  Offline and every provider: OpenFreeMap (default: free vector map data, no key, drawn on
  the phone in light or dark colours with the theme), OpenStreetMap and CARTO dark (raster
  streets), and satellite: USGS (USA only), Esri World Imagery, MapTiler, Mapbox. Offline
  shows only what of the chosen provider is on the phone.
- FR-6.2a Each provider has its own options, opened from it once selected: the fields that
  service takes (API key, access token…, required or optional) and where to get them.
  Values are sealed per provider and sent only to that provider. Choosing a provider that
  lacks a required option opens its options; until set, the map says what is missing.
- FR-6.2b Saving an offline area takes no zoom choice: every area is saved from the overview
  down to the provider's detail (OpenFreeMap vector data to zoom 14, drawn sharp at any
  deeper zoom; OpenStreetMap raster 16; other rasters 17, enlarged beyond). OpenFreeMap areas
  include both colour styles. OpenStreetMap areas are capped at 300 tiles and fetch no lower
  zooms ahead (its tile policy allows modest look-ahead only); the size shows while drawing
  and a too large area is refused ("draw smaller"), never saved without detail.
- FR-6.2c Map data viewed outside saved areas is kept up to 512 MB, oldest dropped first, and
  is shown offline while kept; saved areas are never dropped by it. Settings lists saved
  areas (provider, size, incomplete) with Delete and Clear all.
- FR-6.2d Map requests carry a User-Agent naming the app and its project page.
- FR-6.3 Inactive after 3 missed keep-alives; purged after 24 h.
- FR-6.4 List view: distance and bearing. Rough fixes show uncertainty ("1,9 km (±600 m)");
  a direct Bluetooth/Wi-Fi Direct link that contradicts positions shows
  "nearby (≤100 m: Bluetooth range)".
- FR-6.5 Every member can be renamed locally (only on this phone).

### US-7 — LoRa node
- FR-7.1 A member's own LoRa node, over BLE (Nordic-UART style) or USB serial, is an opaque
  byte pipe for already-encrypted frames; the app knows nothing of LoRa air protocol.
- FR-7.2 Node link state is visible; auto-reconnect; outgoing queue of the 5 newest frames
  while the node is away.

### US-8 — Discovery and radio behaviour
- FR-8.1 A network is findable only while its master has discovery on: "Start discovery" /
  "Stop discovery" on the active network card; on for 10 min after the network starts,
  Invite or an approval, then off by itself. Non-masters see no discovery controls.
- FR-8.2 While findable, the Bluetooth scan response carries network id, join policy and
  name (≤18 bytes); a newcomer lists it from a scan without connecting. Nothing about the
  network is broadcast otherwise.
- FR-8.3 Members advertise a 6-byte members code derived from the network key and rotated
  every 10 min; phones connect only to codes of their own network (±1 window).
- FR-8.4 A listening phone advertises nothing, hosts no Wi-Fi Direct group and connects
  only when the user asks to join.
- FR-8.5 One Bluetooth connection per phone pair per direction; a server-side connection is
  a link only once the peer subscribes; silent links are dropped after 3 beacon intervals
  (≥2 min).
- FR-8.6 Battery: scanning is continuous for 2 min after start or any link change (and
  while listening), otherwise a 5 s burst per minute; advertising at low power (balanced
  while findable).
- FR-8.7 Wi-Fi Direct group credentials derive from the network key and the host's member id
  (no system dialogs, no strangers, a different group per host); the name also changes
  every hour, so a scanner cannot follow a phone by it. A member joins a host's group only
  when that pair agreed on Wi-Fi Direct (or it accepted the host's shared group); the host
  closes a socket whose first authenticated frame comes from a member without such an
  agreement. Being a member of the network means being able to pair with every other member,
  nothing more: there is no group of the network as a whole. Two members that have not
  decided on Wi-Fi Direct yet meet in their pair's group (host as FR-3.5b) and run the
  handshake there; a member that turned it down, or does not offer it, gets no group.
- FR-8.9 Removing a member re-salts the network key: the master draws a new random salt
  (epoch n+1; traffic key = HKDF(epoch-0 key, salt)) and seals it to every remaining
  member's box key (EC P-256, published on its signed card) in a master-signed rekey that
  carries the member list. The removed member keeps only the older keys, so it reads no
  frame, finds no Bluetooth members code and cannot join the Wi-Fi Direct group, all of
  which follow the traffic key. A member that missed the rekey is recognised by its older
  key (Bluetooth also accepts older members codes) and handed the rekey at once; one whose
  box key the master did not have sends its card and is sealed in by a new revision.
  Frames under an older key count for nothing else. The epoch salts and the newest rekey
  are kept with the network.
- FR-8.8 Discovery needs Bluetooth and Location on, on both phones (Android hides nearby
  phones from scans while Location is off). Whatever is off is named in red with "Turn on"
  buttons: on the master's running network card, in place of "findable…" ("Nobody can find
  this network: Bluetooth is off."), and on the joining phone's Discover screen.

### US-9 — Messenger carrier
- FR-9.1 "Send my position via messenger…" shares the newest encrypted frame as a
  `.fmiw1` file / text envelope (Viber preferred, chooser fallback). Always user-initiated;
  the app never writes to the clipboard on its own and writes no file per beacon.
- FR-9.2 A shared envelope opened with the app is verified and treated like a radio frame.

### US-10 — Member list (master)
- FR-10.1 The master signs the member list; each member signs its own card (name, links).
  Signatures are checked against member ids (= key hashes).
- FR-10.2 Every phone keeps a sealed copy per network; beacons carry a fingerprint; phones
  with differing copies exchange them (≤1 per 20 s each), merging the newest: lists
  converge without the master nearby.
- FR-10.3 The master lists every phone it hears with the network key (let in, or joined by
  password).
- FR-10.4 "Members" page for everyone: name, master/you badges, links from the card, last
  heard. On the master it is the admin page: join requests (Accept/Reject + code) and
  Remove.
- FR-10.5 Remove drops a member from the list; every phone ignores its frames from then on;
  its name stays visible. (It keeps the password: a new network is needed to lock it out.)
- FR-10.6 No master transfer: without the master the list just stops changing.

### US-11 — Chat
- FR-11.1 One group chat per network: text and photos, sent as encrypted frames to every
  member (relayed like beacons), shown with the sender's name.
- FR-11.2 History is stored encrypted on the phone (SQLCipher).

### US-12 — Account and name
- FR-12.1 Sign-in (local account or Google) gives the default display name; "Your name" in
  Settings overrides it. The name travels in link control and the member card.
- FR-12.2 Settings: theme, beacon interval, this phone's number (SMS), offline
  maps, LoRa node, Wi-Fi Direct role.
- FR-12.3 App icon: an adaptive icon (you and a friend joined by a path through the
  trees) with a one-colour layer for themed icons. A hand-installed APK lands only in the
  app drawer, so the first launch offers Android's "Add to Home screen" once (not on
  Huawei/Honor launchers, which put every app on the home screen anyway).

## 4. Non-functional requirements

- NFR-1 minSdk 29 (Wi-Fi Direct credentials need Android 10), target latest; Kotlin only.
- NFR-2 INTERNET is used only for map tiles and sign-in; nothing else goes online.
- NFR-3 AEAD (AES-256-GCM) for every frame; Ed25519 device identity (EC fallback) signs
  beacons, management frames, member list and cards.
- NFR-4 Core modules build and test on the JVM; ≥95 % line coverage on the gated scope.
- NFR-5 Diagnostics are debug-level logs under one tag `icegood.fmiw`, silent in release.
- NFR-6 Background: a foreground service with a notification runs radios and GNSS.
- NFR-7 Every visible text is a string resource, in English and 40 translations kept in
  `scripts/l10n` (`gen.py` builds `res/values-*` and checks keys and placeholders); the
  phone's language decides, and Android 13+ offers a per-app language. Right-to-left
  languages lay out mirrored. The app name stays "find me in wood" in every language.

## 5. Out of scope (v1)

iOS; master transfer; locking a removed member out of a password network for good (it
can come back with the password under a new identity, like anyone who has it); foreign meshes;
auto-restart after reboot.
