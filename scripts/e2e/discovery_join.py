#!/usr/bin/env python3
"""
End-to-end check of discovery and joining on two real phones over adb (FR-2.2, FR-8.1, FR-8.8).

Each round does what a person does (time to appear counts from Look for networks):
  master: Networks -> Create network (name + password) -> it starts and is findable;
  joiner: Networks -> Find networks nearby -> Look for networks -> the network must appear
          within --limit seconds -> Enter password (of that network's card) -> Join;
  both:   delete every network, so the next round starts clean.

Usage:  source env.sh; scripts/e2e/discovery_join.py --master <serial> --joiner <serial> [--rounds 5]
        (or FMIW_MASTER / FMIW_JOINER in the environment). Logs of every round go to --out.
Exit code 0 only when every round found the network in time and joined.

Typing: adb types through the phone's on-screen keyboard, which drops or doubles keys that
come fast. Text goes in small chunks and is read back (Show reveals a password); a mismatch
is cleared and typed again.
"""
import argparse, os, re, subprocess, sys, time
import xml.etree.ElementTree as ET

PKG = "com.icegood.findmeinwoods"
ADB = os.path.join(os.environ.get("ANDROID_HOME", os.path.expanduser("~/tools/android-sdk")), "platform-tools", "adb")
TAG = "icegood.fmiw"


class Phone:
    def __init__(self, serial, label):
        self.serial, self.label = serial, label

    def adb(self, *a):
        return subprocess.run([ADB, "-s", self.serial, *a], capture_output=True)

    def sh(self, cmd):
        return self.adb("shell", cmd).stdout.decode(errors="replace").strip()

    # --- screen -----------------------------------------------------------------
    def items(self):
        """(text, bounds) of every node on screen; a dialog dumps only itself."""
        for _ in range(4):
            self.adb("shell", "uiautomator", "dump", "/sdcard/fmiw-ui.xml")
            x = self.adb("exec-out", "cat", "/sdcard/fmiw-ui.xml").stdout.decode(errors="replace")
            if "<hierarchy" in x:
                root = ET.fromstring(x[x.index("<hierarchy"):])
                out = []
                for n in root.iter("node"):
                    t = ((n.get("text") or "") + " " + (n.get("content-desc") or "")).strip()
                    # System dialogs speak the phone's language: their buttons go by id.
                    if n.get("resource-id", "").startswith("android:id/button"):
                        t = "@" + n.get("resource-id").split("/")[-1]
                    out.append((t, list(map(int, re.findall(r"\d+", n.get("bounds"))))))
                return out
            time.sleep(0.5)
        return []

    def find(self, want, exact=False, items=None):
        for t, b in items if items is not None else self.items():
            if (t == want) if exact else (want in t):
                return b
        return None

    def texts(self):
        return [t for t, _ in self.items() if t]

    def tap_at(self, b):
        self.adb("shell", "input", "tap", str((b[0] + b[2]) // 2), str((b[1] + b[3]) // 2))
        time.sleep(0.7)

    def tap(self, want, exact=False, items=None):
        b = self.find(want, exact, items)
        if b is None:  # the screen may have been changing: look once more
            time.sleep(1)
            b = self.find(want, exact)
        if b is None:
            raise Step(f"{self.label}: no '{want}' on screen: {self.texts()[:8]}")
        self.tap_at(b)

    def back_to_tabs(self):
        """Closes dialogs and keyboards until the bottom tabs show."""
        for _ in range(5):
            if self.find("Networks", exact=True):
                return
            self.adb("shell", "input", "keyevent", "KEYCODE_BACK")
            time.sleep(0.5)

    # --- typing -----------------------------------------------------------------
    def type_checked(self, text):
        """Types into the focused field until it reads back exactly."""
        for _ in range(3):
            for k in range(0, len(text), 3):
                self.adb("shell", "input", "text", text[k:k + 3])
                time.sleep(0.3)
            time.sleep(0.4)
            # Reveal a password only now: a tap on Show while the keyboard opens gets lost.
            items = self.items()
            if self.find("Show", exact=True, items=items):
                self.tap("Show", exact=True, items=items)
                items = None
            if self.find(text, exact=True, items=items):
                return
            self.adb("shell", "input", "keyevent", "KEYCODE_MOVE_END")
            self.adb("shell", "input", "keyevent", *(["KEYCODE_DEL"] * (len(text) + 12)))
        raise Step(f"{self.label}: keyboard would not type '{text}'")

    # --- app --------------------------------------------------------------------
    def launch(self):
        self.adb("shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1")
        time.sleep(2)

    def delete_all_networks(self):
        for _ in range(8):
            self.back_to_tabs()
            items = self.items()
            if self.find("Delete “", items=items):  # a confirmation left open
                self.tap("Delete", exact=True, items=items)
                continue
            self.tap("Networks", exact=True, items=items)
            items = self.items()
            if not self.find("Delete", exact=True, items=items):
                return
            self.tap("Delete", exact=True, items=items)
            time.sleep(0.4)
            self.tap("Delete", exact=True)  # the confirmation
        raise Step(f"{self.label}: networks would not delete")

    def radios_on(self):
        """Bluetooth and Location on, as discovery needs (FR-8.8); False when they stay off."""
        if self.sh("settings get global bluetooth_on") != "1":
            self.sh("cmd bluetooth_manager enable || svc bluetooth enable")
            time.sleep(3)
        if self.sh("settings get global bluetooth_on") != "1":
            # Some phones (EMUI) refuse adb: press the app's own button and allow it.
            self.back_to_tabs()
            self.tap("Discover", exact=True)
            if self.find("Turn on Bluetooth", exact=True):
                self.tap("Turn on Bluetooth", exact=True)
                time.sleep(1)
                if self.find("@button1", exact=True):
                    self.tap("@button1", exact=True)
        if self.sh("settings get secure location_mode") == "0":
            self.sh("cmd location set-location-enabled true || settings put secure location_mode 3")
        time.sleep(3)
        return self.sh("settings get global bluetooth_on") == "1" and self.sh("settings get secure location_mode") != "0"


class Step(Exception):
    pass


def round_trip(master, joiner, i, limit, out):
    name = f"e2e{i}x{int(time.time()) % 10000}"
    pw = f"e2e-password-{i}-{int(time.time()) % 100000}"
    for p in (master, joiner):
        p.adb("logcat", "-c")
    r = {"round": i, "name": name, "found_s": None, "joined": False, "error": None}
    try:
        # Radios switched off between rounds (by hand, or by the phone) fail the round plainly.
        for p in (master, joiner):
            if not p.radios_on():
                raise Step(f"{p.label}: Bluetooth or Location is off and adb cannot turn it on")
        # Master creates; the network starts on its own when nothing else runs.
        master.back_to_tabs()
        master.tap("Networks", exact=True)
        master.tap("Create network", exact=True)
        master.tap("Network name")
        master.type_checked(name)
        master.tap("Password (empty")
        master.type_checked(pw)
        for _ in range(4):
            if not master.find("Password (empty"):
                break
            master.tap("Create", exact=True)
        else:
            raise Step(f"master: Create did nothing: {master.texts()[:8]}")
        time.sleep(1.5)
        # Create opens Invite (password + QR code) at once; the card is behind it.
        if master.find("Invite to \u201c"):
            master.tap("Close", exact=True)
        card = master.texts()
        if any("Nobody can find" in t for t in card):
            raise Step(f"master says: {[t for t in card if 'Nobody' in t]}")
        if not any("findable by new members" in t for t in card):
            raise Step(f"master not findable: {card[:12]}")

        # Joiner looks the way a person does.
        joiner.back_to_tabs()
        joiner.tap("Networks", exact=True)
        joiner.tap("Find networks nearby", exact=True)
        if joiner.find("Look for networks", exact=True):
            joiner.tap("Look for networks", exact=True)
        # Timed from the tap a person makes; the network is findable since Create.
        t0 = time.time()
        while time.time() - t0 < max(limit * 2, 30):
            items = joiner.items()
            off = [t for t, _ in items if " is off" in t or " are off" in t]
            if off:
                raise Step(f"joiner shows: {off[0]}")
            if joiner.find(name, items=items):
                r["found_s"] = round(time.time() - t0, 1)
                break
            time.sleep(0.5)
        if r["found_s"] is None:
            raise Step(f"joiner never listed {name}: {joiner.texts()[:6]}")

        # Enter password on that network's own card: the first button below its name.
        items = joiner.items()
        top = joiner.find(name, items=items)[1]
        below = [b for t, b in items if t == "Enter password" and b[1] > top]
        if not below:
            raise Step(f"joiner: no Enter password for {name}")
        joiner.tap_at(min(below, key=lambda b: b[1]))
        joiner.tap("Password")
        joiner.type_checked(pw)
        joiner.tap("Join", exact=True)
        time.sleep(2)
        if joiner.find("Wrong password"):
            raise Step("joiner: Wrong password")
        joiner.back_to_tabs()
        joiner.tap("Networks", exact=True)
        r["joined"] = joiner.find(name) is not None
        if not r["joined"]:
            raise Step(f"joiner: {name} not in Networks after Join")
    except Step as e:
        r["error"] = str(e)
    finally:
        for p in (master, joiner):
            with open(os.path.join(out, f"round{i}-{p.label}.log"), "wb") as f:
                f.write(p.adb("logcat", "-d", "-v", "time").stdout)
    return r


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--master", default=os.environ.get("FMIW_MASTER"))
    ap.add_argument("--joiner", default=os.environ.get("FMIW_JOINER"))
    ap.add_argument("--rounds", type=int, default=5)
    ap.add_argument("--limit", type=float, default=16.0, help="seconds the network may take to appear")
    ap.add_argument("--out", default="build/e2e")
    a = ap.parse_args()
    if not a.master or not a.joiner:
        ap.error("--master and --joiner (or FMIW_MASTER / FMIW_JOINER) are required")
    os.makedirs(a.out, exist_ok=True)
    master, joiner = Phone(a.master, "master"), Phone(a.joiner, "joiner")

    for p in (master, joiner):
        if p.adb("get-state").stdout.decode().strip() != "device":
            sys.exit(f"{p.label} {p.serial} is not connected")
        if not p.radios_on():
            sys.exit(f"{p.label}: Bluetooth or Location is off and adb cannot turn it on")
        p.sh(f"setprop persist.log.tag.{TAG} D; setprop log.tag.{TAG} D")
        p.sh("settings put system accelerometer_rotation 0; settings put system user_rotation 0")
        p.launch()
        p.delete_all_networks()

    results = []
    for i in range(1, a.rounds + 1):
        r = round_trip(master, joiner, i, a.limit, a.out)
        ok = r["error"] is None and r["found_s"] is not None and r["found_s"] <= a.limit
        print(f"round {i}: {'OK  ' if ok else 'FAIL'} found={r['found_s']}s joined={r['joined']}"
              + (f"  {r['error']}" if r["error"] else ""), flush=True)
        results.append(ok)
        for p in (joiner, master):
            p.delete_all_networks()
        time.sleep(2)
    print(f"{sum(results)}/{len(results)} rounds OK (limit {a.limit:.0f} s); logs in {a.out}")
    sys.exit(0 if all(results) else 1)


if __name__ == "__main__":
    main()
