# Transport data-path designs (bottom level)

Sequences: `bt-transport.wsd`, `wifidirect.wsd`, `lora-node.wsd`, `viber-relay.wsd`
(same directory).

## Bluetooth — BLE GATT, custom service (T2.3)

- **Profile**: BLE GATT with one custom 128-bit service. Not SPP/RFCOMM: no pairing
  friction, low power, modern permission model (BLUETOOTH_SCAN/CONNECT), and the trust
  boundary is app-layer AEAD anyway (FR-2.3) — the radio is untrusted.
- **Dual role**: every phone runs GATT server (peripheral, advertising) AND central
  (scanner). Discovery = central scans for the service UUID in adv packets.
- **UUIDs**: service `f11d0a01-5c1e-4a9b-9d3f-2a7b8c6d5e4f`;
  CHAR_WRITE (client→server, write-without-response) `...5e50`;
  CHAR_NOTIFY (server→client, notify) `...5e51`; CCCD standard `0x2902`.
- **MTU**: request 247; chunk payload = MTU−3 = 244 B.
- **Framing**: `[u16be totalLen][FrameCodec bytes]`, split into MTU-sized writes;
  receiver reassembles. Client paces writes on `onCharacteristicWrite` callbacks.
- **Roles symmetric**: both sides reassemble and deliver to SessionManager.

## WiFi Direct (T4.1)

- Group formation via `WifiP2pManager.connect` (random `groupOwnerIntent`); system
  picks group owner (GO at 192.168.49.1).
- Both sides open `ServerSocket(8765)`; the non-owner connects to GO:8765.
- **Framing**: `[u32be len][frame]` on the TCP stream, one socket per peer, send()
  fans out to all sockets.

## LoRa node link (T5.1/T5.1b)

- The phone never touches LoRa: `LoraNodeLink` is a byte pipe to the member's own node
  (FR-7.2). Two link implementations, identical semantics:
  - `BleNodeLink` — Nordic UART Service on the node (`6e400001-…`), write-without-
    response TX, notify RX, MTU 247.
  - `UsbSerialLink` — usb-serial-for-android (CH340/CP210x/FTDI/CDC-ACM), 115200 8N1.
- **Framing**: `0x464D magic + [u16be len][frame]` — magic allows resync after
  reconnects on a stream-oriented pipe.
- Node firmware (Meshtastic/RadioLib) owns everything over the air: modulation,
  addressing, meshing, retries.
- Watchdog: no RX 30 s or link error → LINK_DOWN, TX queue keeps last N=5 (FR-7.5),
  reconnect with exponential backoff (FR-7.4).

## Internet carrier via messenger share (T7.x, US-9)

- No custom P2P (would need a rendezvous/relay server), no Viber bot API (token would
  ship inside the APK). Carrier = Android share intent; envelope =
  `fmiw1:<base64url(wireFrame)>` — the same AEAD frame as on radio, so the messenger is
  an untrusted carrier and positions stay end-to-end encrypted.
- Outgoing is user-in-loop (share sheet pre-targeted at Viber); incoming is a text
  share-target (`ShareInboxActivity`) feeding `SessionManager.acceptExternalWire`.
- No INTERNET permission (NFR-2 intact); works with Telegram/SMS/anything that shares
  plain text.

## Cross-transport invariants

- Wire frame format is identical on all transports (FrameCodec, core/crypto).
- Transports are untrusted: membership = AEAD under network keys (P2/P4).
- Per-edge protocol selection and failover: FR-3.6/3.6a, `edge-switch.wsd`.
