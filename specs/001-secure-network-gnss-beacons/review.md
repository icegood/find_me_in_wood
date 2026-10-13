# Review — 001 against spec.md (2026-10-13)

Each requirement was read against the code. **Fixed** = wrong before this review, now
corrected and covered by a test. **Device** = UI/radio behaviour checked on Pixel 7a + P30
only (no unit test can see it). Tests: `./gradlew test coverageVerify` (green).

| Req | Where | Status |
|---|---|---|
| FR-1.1 password strength, generated length | `ProfileStore.create/strongEnough` | OK — tested |
| FR-1.2 keys from password, sealed store | `NetworkKeysFactory`, `ProfileStore` | OK — tested |
| FR-1.3 creator is master | `MASTER_ME`, `SessionService.startSession` | OK — tested; **fixed earlier today** (master was not passed to the service) |
| FR-1.4 new network starts at once | `MainActivity` create dialog | OK — device |
| FR-2.1 one join policy | `JoinPolicy { PRIVATE }` | **Fixed** — OPEN/CODE paths removed (clear-password accept, hidden filter, warnings) |
| FR-2.2 join by password, checked against heard id | `ProfileStore.join(expected)`, `DiscoverScreen` | OK — tested |
| FR-2.3 ask the master, sealed password, code | `DiscoveryManager`, `SessionService.onRequestJoin` | OK — tested + device |
| FR-2.4 accept must derive the network asked for | `DiscoveryManager.onJoinAccept` | OK — tested; an accept without a sealed password is now refused (test) |
| FR-2.5 joiner records master | `SessionService.onJoinOutcome`, `MemberBook.onMasterLearned` | OK — tested + device; **fixed earlier today** (started from the copy without master) |
| FR-3.2 all links listed, greyed with reason | `LinkAvailability`, `MapScreen.unavailableLinks` | OK — tested |
| FR-3.3 relay, TTL, no duplicates/forgeries | `SessionManager.handleFrame/relay` | OK — tested |
| FR-3.5–3.8 LNP/3 | `LinkNegotiator`, `SessionManager` | OK — `LinkNegotiatorTest`, `LinkE2ETest` |
| FR-3.9 SMS cadence and budget | `SmsLogic`, `SmsBudget`, `SessionService.runLinks` | OK — tested; **not tested on phones** (needs both numbers) |
| FR-4.2 interval 5–300 s, default 60 s | `Prefs` | OK |
| FR-4.4 pause sharing | `SessionManager.sharingPaused`, card "Pause" | **Fixed** — was missing; test `FR-4_4` |
| FR-5.3 signature bound to member id; unsigned refused | `SessionManager.verifyOrigin` | **Fixed** — any member could pin a fake key for another member id, unsigned beacons were accepted; test `FR-5_3` |
| FR-6.3 inactive after 3 missed keep-alives | `PeerTracker(keepAliveMs)` | **Fixed** — used a fixed 60 s, so with a 5 min interval members flickered "unlocated"; test `FR-6_3` |
| FR-6.4 distance texts | `distanceText`, `uncertainty` | OK — tested |
| FR-6.5 local rename | `MemberNaming` | OK — tested |
| FR-7.1–7.2 LoRa node pipe, queue 5 | `LoraTransport` | OK — tested; no node available on device |
| FR-8.1 discovery master-only, 10 min | `SessionBus.setFindable`, `SessionService` | OK — tested + device |
| FR-8.2 info only in scan response while findable | `BleAdvert`, `BluetoothGattWiring` | OK — tested + device (dumpsys) |
| FR-8.3 members code | `BleAdvert.memberTag`, `BluetoothTransport.isMemberTag` | OK — tested + device |
| FR-8.4 listening phone silent | `SessionConfig.listening` | OK — tested |
| FR-8.5 one link per direction, silent dropped | `BluetoothTransport` | OK — tested + device (5 → 2 links) |
| FR-8.6 scan duty cycle | `BluetoothTransport.tickRadios` | OK — tested + device log |
| FR-8.7 Wi-Fi Direct credentials | `GroupCredentials`, `WifiDirectTransport` | OK — tested + device |
| FR-9.1 messenger shares the newest *beacon* | `SessionManager.onFrameSent` | **Fixed** — chat or member-list frames could be shared as "my position"; test `FR-9_1` |
| FR-10.1–10.3 member list, cards, sync | `MemberBook`, `SessionManager.syncBook` | OK — `MemberBookTest`, `MemberBookE2ETest` + device; **fixed**: list sync went over LoRa/SMS/messenger (KBs over a tiny link) — now Bluetooth/Wi-Fi Direct only |
| FR-10.4 Members page | `MembersScreen` | OK — device |
| FR-10.5 removed member ignored | `SessionManager`, `MemberBook` | **Fixed** — stayed on the map/list for 24 h and its SMS reports were accepted; test `FR-10_5` |
| FR-11 chat | `core/chat` | OK — existing tests; photos capped at 40 KB (frame limit 64 KB) |
| FR-12 account and name | `AuthScreen`, `Prefs.currentDisplayName` | OK — device |
| NFR-1 minSdk 29 | `app/build.gradle.kts` | OK |
| NFR-2 internet only for tiles + sign-in | manifest | OK (constitution P1 updated) |
| NFR-5 debug logs, one tag | `DebugLog`, `FmiwApp` | OK — tested |

## Other defects found and fixed

- Stored profiles still carried a random per-network member id and an "owner" id: dead
  data that caused the master bug and made the SMS receiver's "is this my own report"
  check compare against the wrong id. Removed; old profiles still load.
- Wi-Fi Direct tests bound the fixed port 8765: debug and release test runs collided and
  failed at random. The port is injectable; tests use a free one.
- `LinkE2ETest` had a 3 s wall-clock bound that failed on a loaded machine; the
  requirement is proven by counting beacons, the time bound is now generous.

## Open (not defects in code, need a decision or hardware)

- FR-3.9 SMS link never tried on phones (needs both numbers, real SMS may cost money).
- Members never met can be texted only after a link handshake, which needs a radio
  meeting first (spec FR-3.7) — the member list now knows their numbers.
- Satellite tiles cover only the USA.
