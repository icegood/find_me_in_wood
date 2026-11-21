# UI/UX review: on/off features become switches

## Overview
Review the app's own controls against Material 3 and Android guidance. Every binary on/off feature becomes a Switch row: the whole row is tappable, it has the switch role for TalkBack and a 48 dp touch target, and a short hint underneath. Today these are paired buttons or text buttons.

Wi-Fi, Bluetooth and Location (GPS) get always-visible switch rows that show the real system state. Today they are "Turn on …" buttons that only appear when the radio is off. Flipping a row starts the existing system intent. The system window that opens is outside this task (user decision). The switch never flips optimistically: it follows the polled system state.

The app's own toggles also become switches:
- position sharing (now a Pause / Share again text button)
- discovery for the master (now a Start / Stop discovery button pair)
- Wi-Fi Direct "always host the group" (now a Host / Auto button pair)

Multi-choice settings (theme, Wi-Fi Direct band) are not on/off, so they move from fake-selected Button/OutlinedButton pairs to a Material 3 SingleChoiceSegmentedButtonRow.

## Context
- Files involved:
  - `app/src/main/kotlin/com/icegood/findmeinwood/app/MainActivity.kt` (RadioSwitches, network cards with pause/discovery, SettingsScreen theme / Wi-Fi Direct / band)
  - `app/src/main/kotlin/com/icegood/findmeinwood/app/DiscoverScreen.kt` (DiscoveryBlockers: Turn on Bluetooth / Location buttons)
  - `app/src/main/kotlin/com/icegood/findmeinwood/app/UiText.kt` (discoveryBlocked text)
  - new `app/src/main/kotlin/com/icegood/findmeinwood/app/SwitchRow.kt` (shared composable)
  - new `app/src/main/kotlin/com/icegood/findmeinwood/app/RadioToggles.kt` (plain-Kotlin decision logic)
  - `scripts/l10n/app.*.txt` (41 languages), then `scripts/l10n/gen.py`
  - `app/src/test/kotlin/com/icegood/findmeinwood/app/` (JVM tests)
  - `specs/001-secure-network-gnss-beacons/spec.md` (FR-8.8 wording)
  - `design/s2-networks*.pen`, `design/s9-settings*.pen` (pencil MCP tools only)
- Related patterns: decisions stay in plain Kotlin and are JVM-testable (constitution P6); platform calls stay in thin composables / intents. Strings are only added through `scripts/l10n`. Prefs and SessionService setters already exist for sharing paused, findable, p2p group owner and band. Diagnostics go through `DebugLog.d`.
- Dependencies: none new. Material 3 Switch and SegmentedButton are already in compose material3.

## Development Approach
- **Testing approach**: Regular (code first, then tests)
- Complete each task fully before moving to the next
- `source ./env.sh` before Gradle; the gate is `./gradlew build coverageVerify` (95 % lines)
- Keep the uncommitted Wi-Fi Direct and session work in the tree untouched. Don't commit: a push publishes a release.
- **CRITICAL: every task MUST include new/updated tests**
- **CRITICAL: all tests must pass before starting next task**

## Implementation Steps

### Task 1: Shared SwitchRow and radio-toggle logic

**Files:**
- Create: `app/src/main/kotlin/com/icegood/findmeinwood/app/SwitchRow.kt`
- Create: `app/src/main/kotlin/com/icegood/findmeinwood/app/RadioToggles.kt`
- Create: `app/src/test/kotlin/com/icegood/findmeinwood/app/RadioTogglesTest.kt`

- [x] SwitchRow(title, hint, checked, onCheckedChange, enabled, hintColor): a Row with `Modifier.toggleable(role = Role.Switch)`, minimum height 48 dp, title and optional hint on the left, Material 3 Switch on the right with `onCheckedChange = null` so the row owns the click
- [x] RadioToggles (plain Kotlin): `enum Radio {WIFI, BLUETOOTH, LOCATION}`. One function picks the intent action for a radio and SDK level (BT: ACTION_REQUEST_ENABLE with fallback to Bluetooth settings; Wi-Fi: Settings.Panel.ACTION_WIFI on 29+, else Wi-Fi settings; Location: location source settings) as strings, with no Android types. A second function lists which rows to show for a screen (networks: all three; discovery blockers: Bluetooth and Location) and whether a radio is a blocker (state off, shown in error colour)
- [x] write RadioTogglesTest covering every radio, both SDK branches, the fallback and the rows per screen
- [x] run `./gradlew :app:testDebugUnitTest`; it must pass

### Task 2: Wi-Fi / Bluetooth / Location as switches

**Files:**
- Modify: `app/src/main/kotlin/com/icegood/findmeinwood/app/MainActivity.kt`
- Modify: `app/src/main/kotlin/com/icegood/findmeinwood/app/DiscoverScreen.kt`
- Modify: `app/src/main/kotlin/com/icegood/findmeinwood/app/UiText.kt` (only if the blocker sentence changes)

- [x] RadioSwitches: always show a "Radios" card with three SwitchRows (Wi-Fi, Bluetooth, Location) whose checked state is the polled system state (keep the 3 s tick). The current explanation texts (wifi_off_explain, location_off_explain) become the hints, shown in error colour while that radio is off. A tap starts the intent from RadioToggles; the row never flips by itself
- [x] DiscoveryBlockers: keep the red sentence and replace the Turn on buttons with Bluetooth / Location SwitchRows
- [x] turnOnBluetooth / turnOnWifi move onto the RadioToggles action mapping, so the intent logic exists only once
- [x] add a `DebugLog.d` line "ui: radio switch <radio> tapped, state <on/off>"
- [x] update / add tests for any logic moved into UiText or RadioToggles (RadioToggles.tapLog; switch titles radio_wifi / radio_location added to all languages already)
- [x] run `./gradlew :app:testDebugUnitTest`; it must pass

### Task 3: App features as switches (position sharing, discovery, Wi-Fi Direct host)

**Files:**
- Modify: `app/src/main/kotlin/com/icegood/findmeinwood/app/MainActivity.kt`
- Modify: `app/src/test/kotlin/com/icegood/findmeinwood/app/AppPlainTest.kt` (or a new test file next to it)

- [x] network card: "Share my position" SwitchRow (checked = !sharingPaused), calls SessionService.setSharingPaused and bumps pausedTick; the shared/paused text becomes the hint
- [x] network card (master only): "Discoverable" SwitchRow (checked = findableUntil > now), calls SessionBus.setFindable; the UiText.findable countdown is the hint. When DiscoveryBlockers reports a blocker, the switch is disabled. Invite and Members stay buttons, because they are actions
- [x] Settings → Wi-Fi Direct: the Host / Auto pair becomes one SwitchRow "Always host the group" (Prefs.p2pGroupOwner); the p2p_host_hint / p2p_auto_hint texts follow the state
- [x] make sure SessionBus.setFindable and the setSharingPaused prefs path have tests: add or extend JVM tests where coverage is missing (FeatureSwitchesTest, FeatureSwitchPrefsTest; titles share_my_position / discoverable / p2p_always_host added to all languages already)
- [x] run `./gradlew :app:testDebugUnitTest`; it must pass

### Task 4: Multi-choice settings as segmented buttons, plus a review pass

**Files:**
- Modify: `app/src/main/kotlin/com/icegood/findmeinwood/app/MainActivity.kt`

- [x] Theme (System / Light / Dark) and Wi-Fi Direct band (2.4 / 5 GHz): replace the Button/OutlinedButton pairs, whose onClick does nothing for the selected one, with a SingleChoiceSegmentedButtonRow
- [x] review pass over the remaining controls, fixing only what violates the guidelines (offline area Delete / Clear all now ask first; map FABs, chat back and the finding card ✕ got content descriptions, FABs a 48 dp touch target; navigate_back / center_on_me added to all languages already):
  - one primary (filled) button per card; others outlined or text
  - destructive actions (Delete, Remove, Clear all) only as text/outlined buttons with confirmation, as today
  - no fake toggle buttons left
  - touch targets of at least 48 dp
  - icon-only map FABs get contentDescription strings
- [x] grep check: no on/off feature implemented as a Button / OutlinedButton pair remains in `app/` or `feature/`
- [x] update / add tests for any logic touched (ChoicesTest)
- [x] run `./gradlew :app:testDebugUnitTest :feature:map:testDebugUnitTest`; both must pass

### Task 5: Strings in all languages

**Files:**
- Modify: `scripts/l10n/app.*.txt` (all 41), `scripts/l10n/map.*.txt` if new map content descriptions are added

- [x] add the new keys (radios card title, switch titles wifi / bluetooth / location, share_my_position, discoverable, p2p_always_host, FAB content descriptions) to app.en.txt and every translation, with matching placeholders (already added in Tasks 2–4; all 41 app and map files have them)
- [x] remove keys that are no longer used (turn_on_*, share_again, pause, start_discovery, stop_discovery, p2p_host, p2p_auto) if nothing references them any more
- [x] run `python3 scripts/l10n/gen.py`; it must succeed and regenerate strings.xml
- [x] run `./gradlew :app:testDebugUnitTest` (EnglishTexts checks); it must pass

### Task 6: Designs and spec

**Files:**
- Modify: `design/s2-networks.pen`, `design/s2-networks-light.pen`, `design/s9-settings.pen`, `design/s9-settings-light.pen` (pencil MCP tools only)
- Modify: `specs/001-secure-network-gnss-beacons/spec.md`

- [ ] networks screen design: Radios card with three switch rows; network card with Share my position / Discoverable switches
- [ ] settings screen design: segmented theme and band, "Always host the group" switch
- [ ] FR-8.8 in spec.md: "switches that show the radio state and open the system control" instead of "buttons to turn it on"

### Task 7: Verify acceptance criteria

- [ ] `source ./env.sh && ./gradlew build coverageVerify` (build includes lint); must pass
- [ ] coverage gate at 95 % lines (above the template's 80 %)
- [ ] `./gradlew :app:assembleDebug` succeeds

### Task 8: Update documentation

- [ ] `app/CLAUDE.md`: note that on/off features use SwitchRow, multi-choice uses segmented buttons, and radio switches show the system state without flipping themselves
- [ ] update the RadioSwitches / DiscoveryBlockers class comments to the new behaviour

## Post-Completion
- On a phone (phone-testing skill, debug build), check each radio switch with the radio on and off; on Android 13+ make sure the Bluetooth switch follows once the system prompt is accepted
- TalkBack check: rows announce "switch, on/off"
- Commit and push only when the user asks
