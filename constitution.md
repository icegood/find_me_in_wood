# Constitution — find_me_in_wood

Non-negotiable principles for all features and code. Any PR violating these is rejected.

## P1. Offline-first
The app MUST be fully functional with no internet access: no cloud, no push, no online
accounts. The only connectivity used is device-to-device (WiFi Direct, Bluetooth, LoRa).

## P2. Secure network by construction
A network is a **name + shared secret**, created once and agreed out-of-band.
- Every frame on the air is encrypted AND authenticated (AEAD). No plaintext frames, ever.
- Frames that fail authentication are silently dropped and counted.
- Keys never leave the device and are never logged.

## P3. Transport-agnostic core (Strategy pattern)
All domain logic depends only on a `Transport` interface. WiFi Direct, Bluetooth and
LoRa are interchangeable strategies. Adding a transport MUST NOT require changes to
core, crypto, or UI modules.

A LoRa node attached over BLE is an **opaque byte pipe**: the app never encodes knowledge
of how nodes communicate with each other over the air; it only exchanges already-encrypted
frames with the local node.

## P4. Per-edge transport selection, managed relay
A network is a connectivity graph: every edge (pair of members) may use its own
transport strategy (WiFi Direct / Bluetooth / LoRa), agreed by the members of that
edge. A member MAY run several transports concurrently. Members relay
authenticated frames across edges under flood control (seen-cache + TTL + split
horizon); relaying never decrypts anything for non-members and never rebroadcasts
frames that fail AEAD. The active transports of a session are visible in the UI.

## P5. Location privacy & consent
GNSS position is broadcast only while the user has sharing enabled for that network.
Sharing can be paused per-network in one tap. Peer history older than 24 h is purged.

## P6. Testability without hardware
Crypto, frame codec, peer-tracking, and beacon scheduling are pure Kotlin (JVM-testable).
Radio transports sit behind interfaces and are faked in tests.

## P7. Battery respect
Default GNSS fix interval and beacon cadence must keep typical overnight standby under
5 % battery. Foreground services show a persistent notification whenever radios/GNSS run.
