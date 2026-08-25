# Handshake & discovery — 001 (serverless, P2P)

No server exists anywhere in this protocol. All steps run over the selected Transport
(BLE / WiFi Direct / LoRa). Two frame classes:

- **Management frames** (cleartext, signed by sender's Ed25519 key): `HELLO`,
  `JOIN_REQ`, `JOIN_ACK`, `JOIN_ACCEPT`, `JOIN_REJECT`
- **Traffic frames** (AEAD under trafficKey): beacons — unchanged (US-5)

## Join policies (per network, chosen at creation)

| Policy | HELLO name | Joining |
|---|---|---|
| `OPEN` | broadcast | anyone sends JOIN_REQ, gets secret in clear ACCEPT |
| `PRIVATE` | broadcast | owner taps Accept; secret sent via ECIES; SAS verify |
| `CODE` | hidden (name omitted) | current PSK model, no handshake (US-2) |

## Sequence — PRIVATE (default)

PlantUML sources: `handshake-approved.wsd`, `handshake-open.wsd`, `handshake-code.wsd`
(same directory; `.wsd` = PlantUML sequence diagram).

- **Who answers JOIN_REQ:** any member may relay it to the owner (owner = `creatorMemberId`,
  stored in every profile, advertised in HELLO). In v1 only the owner processes JOIN_REQ.
- **SAS** (short authentication string) defeats active MITM on the radio link: 6 digits
  derived from the handshake transcript, compared by humans. Skipping SAS is allowed only
  for `OPEN`.
- **ECIES**: X25519 ephemeral + XChaCha20-Poly1305 — the secret never appears in clear
  even though the channel is cleartext.
- **Replay**: nonceB echoed in ACK/ACCEPT; transcript signature binds all fields.
- **Ownership** is informational (creator identity), not a PKI: trusting "owner Misha"
  = trusting you can see Misha's device and SAS match. Ownership transfer = owner sends
  signed OWNER_TRANSFER management frame (v2, out of scope).
- **Airtime budget**: HELLO ≤ 80 B, ≤1 per member per 60 s; on LoRa only the owner emits
  HELLO, name truncated to 16 chars.
- **Privacy**: `CODE` networks are invisible by name (HELLO omits name, netId only) —
  indistinguishable from noise without the secret.

## Sequence — OPEN

Same as above minus SAS and with `JOIN_ACCEPT{secret in clear}`; UI warns "anyone nearby
can read this network".

## Sequence — CODE (unchanged US-2)

No HELLO name, no handshake. Out-of-band `name#secret#vtag` → first AEAD beacon is the
key confirmation (FR-2.3).
