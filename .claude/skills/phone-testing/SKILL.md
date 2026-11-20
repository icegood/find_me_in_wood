---
name: phone-testing
description: Test find me in wood on real Android phones over adb — install builds, capture and read logs, drive the UI, set up a network between two phones, debug Wi-Fi Direct/Bluetooth links. Use when the user asks to gather logs from the phones, reproduce something on devices, connect phones, or check why phones lose or can't find each other.
---

# Testing on phones

Phone serials and models: `CLAUDE.local.md`. Always `source ./env.sh` first (adb, JDK).

## Builds

- Only **debug** builds write the app log (`icegood.fmiw`); release builds are silent.
  For any investigation install `app/build/outputs/apk/debug/app-debug.apk`.
- Debug and release share the release key, so `adb install -r` switches between them and
  keeps the phone's networks and history. Never uninstall to fix a signature mismatch
  without asking: it wipes the user's data.
- The version on a phone: `adb shell dumpsys package com.icegood.findmeinwoods | grep -m2 -E "versionName|lastUpdateTime"`.

## Installing on Huawei (EMUI)

- Newer EMUI asks on screen for every ADB install, and `adb install` waits until someone
  taps it. Behind a lock screen it hangs forever: run installs with `timeout`, never leave
  one waiting.
- Settings that cause it: `secure:adb_install_need_confirm=1` (the "Monitor apps installed
  by ADB" switch in Developer options; adb is not allowed to change it, so the user must
  flip it) and `global:verifier_verify_adb_installs=1` (adb can set it to 0).
- A USB device that shows only MTP + Mass Storage (`lsusb -v`) means USB debugging was
  switched off on the phone: ask the user to turn it back on.

## Logs

- Make the ring buffer big right away: `adb -s <serial> logcat -G 16M`. Pixel defaults to
  256 KiB (about 3 minutes); after a test away from USB the interesting part is gone.
- Dump: `adb -s <serial> logcat -d -v threadtime > <scratchpad>/<serial>.log`.
- On-device capture that survives unplugging:
  `adb shell 'nohup logcat -v threadtime -f /data/local/tmp/fmiw.log -r 16384 -n 8 >/data/local/tmp/fmiw.out 2>&1 &'`
  (`setsid … &` dies with the adb session; this form gets reparented to init). EMUI kills
  it after a while, so the 16 MiB ring buffer is the dependable record. Check with
  `ps -A | grep " logcat$"`.
- Ask the user for the wall-clock times of what they did (moved apart, came back);
  phone clocks may differ by a second or two.

## Driving the UI

- Read the screen as text with tap centers:
  `adb shell uiautomator dump /sdcard/ui.xml` then parse `text=… bounds=[x1,y1][x2,y2]`.
  A screenshot (`adb exec-out screencap -p`) only when the dump is not enough; a black
  screenshot means the screen is off or a secure window is showing.
- Wake: `input keyevent KEYCODE_WAKEUP`; swipe to unlock works only without a PIN. Keep the
  screen on while on USB: `svc power stayon usb` (the user may need to set "Stay awake").
- Turn radios on: `svc wifi enable`, `cmd location set-location-enabled true`; Bluetooth
  through the app's "Turn on Bluetooth" button (system dialog → Allow).
- Never accept another app's terms, and never type the user's account credentials: ask.
- Launch: `am start -n com.icegood.findmeinwoods/com.icegood.findmeinwood.app.MainActivity`.

## Connecting two phones

1. Master phone: Networks → its network → "Start discovery" (findable for 10 min, needs
   Bluetooth on).
2. Joiner: Networks → "Find networks nearby" → "Look for networks" → network card →
   "Ask a member" (or "Enter password").
3. Master: Members → join request → Accept; both show the same 6-digit code; joiner taps
   "Codes match".
4. Peers tab on both → expand the peer (▾) → allow the wanted link (e.g. Wi-Fi Direct only).
   The line under it says who hosts the group ("this phone creates the group, X joins").
Scripted version: `scripts/e2e/discovery_join.py`.

## adb over Wi-Fi (one cable, two phones, or phones apart)

- `adb -s <serial> tcpip 5555; adb connect <phone-ip>:5555` (phone IP from
  `ip -f inet addr show wlan0`), then unplug. Lasts until reboot or `adb usb`.
- Android 11+: Developer options → Wireless debugging → `adb pair`, no cable needed.
- It shows up as an extra entry in `adb devices`. Close it when done: `adb disconnect`,
  `adb -s <serial> usb`.

## Reading a Wi-Fi Direct problem

Notes on platform behaviour and log signatures: `transport/wifidirect/CLAUDE.md`. Grep the
dump for `wpa_supplicant` (`P2P-GROUP-STARTED … freq=`, `GROUP-REMOVED … reason=`,
`for join with frequency`, `Scan included frequencies`, `ASSOC-REJECT`) next to the app's
`wifi direct: A -> B` transitions and `rx WIFI_DIRECT from <id>` lines.
