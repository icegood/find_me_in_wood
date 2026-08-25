# UI Design Brief — find_me_in_wood

Feed to Stitch/pen.dev before generating screens. Screen prompts live in
`stitch-prompts.md`.

## 1. Product feel

Outdoor utility app used one-handed in forest/field conditions: high-glance legibility,
glove-friendly targets, zero decoration that wastes attention.

**Vibe adjectives:** rugged, utilitarian, high-contrast, calm-dark.

## 2. Theme & tokens

| Token | Value | Why |
|---|---|---|
| Base theme | Dark-first (also light variant) | night/dusk use, OLED battery |
| Background | `#121410` (near-black w/ green cast) | outdoor eye comfort |
| Surface | `#1C1F1A` | cards |
| Primary | `#7ADB4F` (signal green) | actions, "me" dot, live states |
| Secondary | `#F2B84B` (amber) | warnings, no-fix, stale peers |
| Danger | `#E5533D` | auth failures, link down |
| Text | `#EDEFEA` primary / `#9AA096` secondary | ≥4.5:1 contrast |
| Font | Roboto / Inter; monospace (JetBrains Mono) for IDs, keys, coords | join codes must be OCR-able |
| Touch targets | ≥48 dp, spacing ≥8 dp | gloves |
| Radii | 12 dp cards, fully rounded chips/buttons | |
| Icons | Material Symbols Outlined | matches pen.dev built-ins |

Status language (used consistently on every screen):

- **Fix chip**: GPS ok (green, accuracy m) / acquiring (amber pulse) / none (amber)
- **Link chip**: aggregate session state, e.g. `3 peers · 2 links`, `node ready` /
  `link down` — never a single protocol (per-edge model, P4)
- **Peer freshness**: live <3 min green ring; stale amber; purged hidden

## 3. Screen inventory (maps to spec)

| # | Screen | Spec source |
|---|---|---|
| S1 | Permissions/onboarding primer | plan §3.8 |
| S2 | Networks list (`name @ PROTOCOL`) | US-1/3, P4 |
| S3 | Create network (name + generated key → join string) | US-1 |
| S4 | Join hub: nearby discovered networks (owner, members, policy chip) + join-code entry | US-8, US-2 |
| S5 | Map view — main screen (self+peers, unlocated badge, tile sources) | US-6, FR-6.5 |
| S6 | Members list: per-member edge line (`LoRa · direct`, `via Misha · LoRa`) | US-3, FR-6.4 |
| S7 | Network detail: CONNECTIONS (member chips → per-edge transport selector), LoRa node, per-network sharing toggle, diagnostics | US-3, FR-7.4, T5.5 |
| S8 | LoRa node picker (BLE\|USB link type, scan results, pin device) | US-7 |
| S9 | User settings: appearance (theme), map tiles, defaults for new networks, about | FR-6.5, app-global |
| S10 | Join handshake progress: request → waiting for owner → SAS compare → connected (joiner side, PRIVATE) | US-8, FR-8.3 |
| S11 | Owner accept dialog: "Dana asks to join", SAS compare, Reject/Accept | US-8, FR-8.4 |

Navigation: single-Activity, bottom bar only on main screens (Map / Members / Networks),
detail screens push on top.

## 4. Rules for any generator

- Every screen shows the **fix chip** and active **link chip** in the top app bar (P4).
- No internet-dependent imagery: flat vector icons, offline-map style tiles only.
- Numbers (coords, keys, counters) always monospace.
- Destructive/crypto actions (show secret, leave network) require confirmation sheet.
