# Join handshake — 001

Current requirements: `spec.md` FR-2.1–2.5 (join), FR-8.1–8.4 (discovery), FR-10 (member list).

- Discovery is the master's Bluetooth scan response while discovery is on (no HELLO frames).
- The joiner connects to a phone advertising the network and sends `JOIN_REQ` (signed,
  ephemeral ECDH key). Only the master answers: `JOIN_ACCEPT` carries the password sealed
  to the joiner's key, or `JOIN_REJECT`. Both phones show the same 6-digit code.
- An accept counts only if its password derives the network asked for.
- One policy: ask the master, or enter the password.

`handshake-approved.wsd` shows the message flow; `handshake-open.wsd` and
`handshake-code.wsd` describe removed policies (OPEN, CODE) and are kept for history only.
