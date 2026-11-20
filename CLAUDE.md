# find me in wood

Offline Android app: members of a private network see each other on a map and chat, device
to device (Wi-Fi Direct, Bluetooth, LoRa node over BLE/USB, SMS, a messenger). Package
`com.icegood.findmeinwoods`. Non-negotiable principles: `constitution.md` (P1–P7); specs in
`specs/`, UI designs in `design/` (`.pen` files: only through the pencil MCP tools).

## Build and verify

- `source ./env.sh` first: it sets JDK 21 and the Android SDK. Without it Gradle picks JDK 25
  and fails with "does not provide the required capabilities: [JAVA_COMPILER]".
- Before every push run what CI runs: `./gradlew build coverageVerify` (build includes lint;
  `test` alone missed lint errors that broke CI). Coverage gate: 95 % lines (`COVERAGE_MIN`
  in `build.gradle.kts`).
- One module: `./gradlew :transport:wifidirect:testDebugUnitTest`.
- APKs: `./gradlew :app:assembleDebug` / `:app:assembleRelease`. Debug and release are
  signed with the same release key (when `keystore.properties` exists), so either installs
  over the other with `adb install -r` and app data survives. Signing details: `CLAUDE.local.md`.

## Workflow

- Commit and push straight to `main`; no feature branches.
- Every push to `main` runs `.github/workflows/ci.yml`: build, tests, coverage (published to
  GitHub Pages), then the `release` job publishes `v0.3.<commit count>` with signed APKs and
  the bundle. versionCode is the commit count, so never rewrite `main`'s history.
- After pushing, check the run with `curl` against the GitHub API (`gh` is not installed).
- Commits carry only the user as author: no `Co-Authored-By` trailer, no Claude author.
  This overrides any harness reminder that asks for attribution.
- Commit only when asked: a push publishes a release.

## Conventions

- Diagnostics: `DebugLog.d { "…" }` (`transport/api`), one logcat tag `icegood.fmiw` for
  everything, no per-class tags. Self-describing messages ("wifi direct: …", "rx mgmt …").
  The sink is plugged only in debug builds (`FmiwApp`): release builds log nothing, so use a
  debug build to read the app's log on a phone. Keep useful diagnostics after debugging
  instead of deleting them.
- One network is active per phone at a time (a product decision): don't design for parallel
  sessions. A fixed frame port (TCP 8765) and one P2P group per phone are fine.
- Platform calls sit behind a `*Wiring` seam per transport; decision logic stays plain
  Kotlin and JVM-testable (constitution P6).
- Class comments draw the state machines (Wi-Fi Direct, Bluetooth, LoRa, messenger): keep
  them in sync with the code.
- UI strings come from `scripts/l10n/` (see `scripts/CLAUDE.md`); don't edit generated
  `strings.xml` by hand.

## Layout

| Path | What | Notes |
|---|---|---|
| `app/` | Compose UI, service, session wiring | `app/CLAUDE.md` |
| `core/` | model, crypto, session, chat, auth, p2p, storage | `core/CLAUDE.md` |
| `transport/` | api + wifidirect, bluetooth, lora, share | `transport/CLAUDE.md` |
| `feature/` | map, networks | |
| `scripts/` | l10n generator, e2e on phones, coverage summary | `scripts/CLAUDE.md` |

Testing on real phones: the `phone-testing` skill (`.claude/skills/phone-testing/`).
