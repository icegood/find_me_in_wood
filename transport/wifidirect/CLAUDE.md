# transport/wifidirect/

Group lifecycle: the state machine in `WifiDirectTransport`'s class comment; every change
goes through `moveState` (logged as `wifi direct: A -> B`).

## Platform facts (seen on devices)

- **Android 10 band bug.** Android 10 hands the `WifiP2pConfig` band constant to the
  supplicant unconverted: `GROUP_OWNER_BAND_5GHZ` (2) is read as "the 2 GHz band", so a joiner
  scans 2.4 GHz only and never finds a 5 GHz host until an unrelated full scan happens to
  cover its channel. `WifiDirectWiring.operatingBand` asks for AUTO before Android 11.
  Supplicant log signature: `Scan SSID DIRECT-… for join with frequency 2` followed by
  scans of 2412–2472 only.
- **Hourly group rename.** Group credentials derive from the network key, the host and the
  hour (`SessionManager.applyWifiDirect`, `wifiDirectSecret(key, host, hour)`). At every
  full hour the name changes. `GroupPlan.id` (the host) tells `setGroups` it is the same
  group: while members are linked it keeps the old name (`renamePending`) and only re-forms
  under the new one when the last socket closes. Before this fix the link dropped every hour.
- **Several groups per phone** (3+ members on Wi-Fi Direct, FR-3.5b): a phone that hosts
  one pair and joins another, or joins two hosts, serves them in turn (`rotate`, 15 s after a
  link, or without one 25 s hosting and 12 s joining). Each phone rotates on its own clock, so a joiner often arrives
  while its host is away in another group: links flap every few seconds. Seen with a
  forgotten third member (Pixel) lying nearby with the app running.
- **Channel sweep was the slow part of a join.** Without a channel, the VOG-L29 swept
  2.4 + 5 GHz (`for join with frequency 0`), ~1.5 s per pass, and needed 8.7 s to find a
  host next to it. Groups now run on `GroupCredentials.frequency` (from the secret);
  `for join with frequency 2412` joined in 0.8 s. DHCP adds 2.5-3 s after that.
- **No Wi-Fi rendezvous any more:** the owner used to host a group named from the network
  key alone, so a password joiner found it without Bluetooth (first frames 5.4 s cold). Now
  joining and the first handshake run over Bluetooth (or QR); groups exist only for pairs
  agreed on Wi-Fi Direct.
- `am force-stop` leaves the P2P group up in the system: toggle Wi-Fi for a cold test.
- **Subgroups:** `LinkNegotiator.pairGroupNeeded` skips a pair's own group when both
  members are surely in one host's group (`LinkCtl.hubs`: a picked host, or the owner by
  default), so 3+ phones need not take turns. A member that agreed Wi-Fi Direct with nobody
  in a shared group (config "JAD–owner Bluetooth only") gets a pair group, and the other
  phone then takes turns: hosting ~10 s, ~6 s in the other group; the slow EMUI joiner
  (6–16 s to join + DHCP) mostly misses its slot. The host reports its group's real channel
  (`TransportEvent.HostedChannel` → `LinkCtl.freq`), joiners use it.
- A drop the host caused reaches the member as `P2P-GROUP-REMOVED … reason=GO_ENDING_SESSION`
  (only while in range). A real range loss shows up as lost beacons/deauth instead.
- Huawei (EMUI) rejected associations with `CTRL-EVENT-ASSOC-REJECT status_code=5201`
  (vendor code, cause unknown).

## Reading device logs

Framework side: `wpa_supplicant` lines (`P2P-GROUP-STARTED … freq=`, `GROUP-REMOVED`,
`for join with frequency`, `Scan included frequencies`, `ASSOC-REJECT`) and `WifiP2pService`.
App side: tag `icegood.fmiw` (debug builds only). Workflow: the `phone-testing` skill.
