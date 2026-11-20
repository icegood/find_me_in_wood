# app/

- `FmiwApp` plugs `DebugLog` into `Log.d` only when `BuildConfig.DEBUG`.
- `SessionService` owns the running session (foreground service, constitution P7);
  `SessionBus` carries its state to the UI. `MainActivity` hosts the tabs (Map, Networks,
  Chat, Peers, Discover, Settings); the Peers tab is `feature.map.PeerList`.
- Version: `versionCode` = commit count, `versionName` = `0.3.<count>` (`build.gradle.kts`).
- Signing: release config from repo-root `keystore.properties` (gitignored); without it the
  release APK is unsigned with a warning, never debug-signed. The debug build uses the same
  release key when present. Details: `CLAUDE.local.md`.
- Strings: generated from `scripts/l10n/app.<lang>.txt`; edit those, then run the generator.
