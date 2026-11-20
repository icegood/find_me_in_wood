# transport/

- `api/`: the `Transport` interface every radio implements (constitution P3), `TransportEvent`,
  `FakeTransport` for tests, `DebugLog`. Core, crypto and UI depend only on this module.
  Adding a transport must not change core, crypto or UI.
- Each radio module splits into `<Name>Transport` (decisions, plain Kotlin) and
  `<Name>Wiring` (every framework call, broadcast and socket). Tests subclass the wiring
  (`open` methods) instead of touching Android. Keep new framework calls in the wiring.
- A LoRa node is an opaque byte pipe: never encode how nodes talk to each other.
- Time-driven transitions run from a poll; constants for timeouts live in the companion
  object, `internal var` where tests shorten them. Each constant's comment says why that
  value, from field observations; update it when a value changes.
- `wifidirect/` has its own notes: `transport/wifidirect/CLAUDE.md`.
