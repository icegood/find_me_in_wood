# core/

- `model`, `crypto`, `session` are pure Kotlin and JVM-tested (constitution P6). No Android
  types in them beyond what `session` already wires.
- `crypto`: every frame is AEAD encrypted and authenticated; frames failing auth are dropped
  and counted, never logged. Keys never leave the device and never go into logs.
- `session/SessionManager`: frame receive path (auth → replay window → link control →
  roster/rekey/beacon), link negotiation (LNP/3, `LinkNegotiator`), the Wi-Fi Direct group
  plan (`applyWifiDirect`), and the beacon sequence (starts at `clock()/1000`, so it keeps
  increasing across restarts). `PeerTracker` ignores beacons whose seq is not newer.
- `chat`: history lives in one SQLCipher DB `chat.db` for the whole app (`ChatStore`); every
  row carries its network (`network` = network id hex, schema v2) and `ChatRepository` is
  built per network, so only that network's messages show. Deleting a network deletes its
  messages and their photos (`ChatRepository.deleteNetwork`). Rows from v1 have network `''`
  and show nowhere. Schema changes need a real migration in `onUpgrade` (`ALTER TABLE`),
  never drop the table.
- `storage`: `SecureDb` (SQLCipher), `SealedFiles` (Keystore-sealed files, e.g. chat photos).
