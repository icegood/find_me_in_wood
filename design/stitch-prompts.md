# Stitch prompts — find_me_in_wood

Usage: at stitch.withgoogle.com choose **Mobile** project, paste P0 first (sets theme),
then generate screens S1–S8 one prompt at a time. Refine with the tweak snippets at the
bottom. The same prompts work as pen.dev AI instructions (Cmd/Ctrl+K) against a `.pen`
file — pen.dev also has Material Symbols built in.

## P0 — Master theme prompt

```
A dark-themed rugged Android app called "find me in wood" for offline group location
sharing in forests using WiFi Direct, Bluetooth and LoRa devices. Utilitarian,
high-contrast, calm. Near-black background #121410 with a slight green cast, surface
#1C1F1A cards, signal green #7ADB4F primary actions, amber #F2B84B warnings, red-orange
#E5533D errors, off-white text #EDEFEA. Sans-serif font; all coordinates, keys and IDs in
monospace. Rounded 12dp cards, fully rounded buttons and status chips, Material Symbols
outlined icons, minimum 48dp touch targets. Every screen has a top app bar with a GPS fix
chip on the left and a connection chip showing the active protocol on the right.
```

## S1 — Permissions primer

```
An Android onboarding screen titled "Ready for offline work". Three large list rows with
outlined icons: location pin "GNSS position — to show you on the map", wifi
"Nearby devices — WiFi Direct discovery", bluetooth "Bluetooth — peers and LoRa node".
Each row has an amber "required" tag and a short one-line explanation underneath.
Primary green rounded button at bottom "Grant permissions", secondary text button below
"Why no internet?". Dark background #121410 per theme.
```

## S2 — Networks list

```
The home screen of a dark offline-location Android app: top app bar with app name,
a green GPS fix chip reading "GPS ±4 m", and a connection chip "BT · 2 peers". Below,
a section "Your networks" with two 12dp-rounded surface cards: card one titled
"hunt-2026" with a monospace subtitle "@ BLUETOOTH · 3 members", a small green live dot
and last-seen text "updated 12 s ago"; card two "mushroom-crew" with subtitle
"@ LORA · node disconnected" in red-orange and an amber stale dot. Each card has a
chevron. Bottom navigation bar with three items: Map (selected), Members, Networks.
Floating action button with a plus icon bottom-right above the bar.
```

## S3 — Create network

```
A dark form screen with back arrow in the top app bar, title "Create network". A labeled
text field "Network name" with placeholder "e.g. hunt-2026". Below, a card "Secret key"
containing a generated key in monospace, e.g. "9f2k qv7m pzxd wtr5", with two icon
buttons: refresh (regenerate) and copy. Helper text "Share name + key only in person".
A switch row "Use my own passphrase instead". Primary fully-rounded green button at the
bottom "Create network". GPS fix chip and protocol chip in the top bar.
```

## S4 — Join hub (discovery + code)

```
A dark join hub screen titled "Join network" with a back arrow and an amber "GPS —" chip.
Section "NEARBY NETWORKS" with an amber "scanning" label and thin amber scanning line.
Two rounded cards: card one "hunt-2026" with a green outlined chip "PRIVATE · ask owner" and a
monospace subtitle row "owner Misha · 3 members" with a green dot; card two
"mushroom-crew" with an amber outlined chip "OPEN" and subtitle "owner Olya · 5 members".
Each card has a chevron. A thin divider with the word "or" centered. Section
"HAVE A JOIN CODE?" with one monospace text field "name#secret#vtag" placeholder
"hunt-2026#9f2k…#wtr5" and a paste icon. Primary green rounded button "Join with code"
at the bottom. Footnote: "All traffic stays encrypted between members."
Background #121410, cards #1C1F1A, primary #7ADB4F, amber #F2B84B, text #EDEFEA.
```

## S5 — Map view (main)

```
Main map screen of a dark outdoor app. Full-bleed offline-style map with muted dark
terrain tiles and thin contour lines. Top app bar overlays the map: left green chip
"GPS ±4 m", right chip "LORA · 3 peers". On the map: a green circular marker labeled
"You" with a wide accuracy halo, and two peer markers - an orange circle labeled "Misha"
with a smaller halo, and a gray-green pin labeled "Dana" marked unlocated with a tiny
amber clock badge. Bottom-left floating card "Members 3 · located 2", bottom-right a
locate-me crosshair button. Bottom navigation bar Map selected, Members and Networks.
```

## S6 — Members list (mapless)

```
Dark member-list screen titled "hunt-2026 @ BLUETOOTH" with the GPS fix chip on the left
of the top bar. A vertical list of rounded cards, one per member: each card has an avatar
circle with initial, nickname, and right-aligned monospace distance and bearing, e.g.
"Misha  1.2 km  NE 42°" with a small compass-arrow icon rotated to bearing, and a green
live dot. One card shows "Dana  unlocated · seen 4 min ago" with an amber dot. Tapping a
card would open its position on the map. Bottom navigation Members selected.
```

## S7 — Network detail / settings

```
Dark settings screen for network "hunt-2026", back arrow in top app bar, chips for GPS
fix and link state. Section "Protocol" with a segmented selector of three options:
WiFi Direct, Bluetooth (selected, green), LoRa, and helper text "All members must use the
same protocol to see each other." Section "LoRa node": a row with radio-tower icon, node
name "TTGO-TBeam-1", status "ready" in green, and a chevron opening the picker. Section
"Sharing": switch row "Broadcast my position" currently on, caption "Keep-alives continue
when off". Section "Diagnostics": monospace counters "frames sent 214 · received 198 ·
auth-failed 0 · dropped 1". Red-toned outlined button "Leave network" at the very bottom.
```

## S8 — LoRa node picker

```
Dark modal sheet over dimmed background, title "Choose your LoRa node", subtitle
"The phone talks to this device over Bluetooth LE; nodes handle the rest." A scanning
indicator line, then a list of discovered BLE devices as rows: signal-strength icon,
device names in monospace like "TBeam-A3F2", RSSI value "-61 dBm", one row already
starred/pinned with label "last used". Rows are 56dp tall. Text button at the bottom
"Scan again" and a primary green button "Use selected node". A footnote: "Node firmware
must expose a serial (Nordic UART) service."
```

## Tweak snippets (one change at a time)

```
Make every status chip pill-shaped with 2dp colored outline instead of filled.
Switch the map peer markers to teardrop pins with initials inside.
Add an empty-state version of the networks list with an illustration-free centered icon
of two radios and text "No networks yet" plus a create button.
Show the light theme variant of this screen keeping identical layout.
Increase all body text to 16sp and add 25% more vertical padding between list rows.
```

## After generating

- Export Stitch output → Figma (or code) into `design/exports/<screen>.png` for reference.
- In pen.dev: save `.pen` files under `design/`, then ask MCP-connected OpenCode:
  "Recreate the S5 map screen from design/ as Compose components in feature/map".
