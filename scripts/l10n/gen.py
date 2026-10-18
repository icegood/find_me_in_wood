#!/usr/bin/env python3
"""Builds res/values*/strings.xml of each module from <module>.<lang>.txt and checks them.

Source lines: key=value (key!=value: not translatable, English only; key[]=a|b: array).
A translation must have exactly the English keys and, per key, the same placeholders."""
import glob, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = "/home/ice/external/find_me_in_wood"
MODULES = {"app": "app/src/main/res", "map": "feature/map/src/main/res"}
PH = re.compile(r"%(\d+\$)?[sd]")


def parse(path):
    out = {}
    for n, line in enumerate(open(path, encoding="utf-8"), 1):
        line = line.rstrip("\n")
        if not line.strip() or line.startswith("#"):
            continue
        if "=" not in line:
            sys.exit(f"{path}:{n}: no '='")
        k, v = line.split("=", 1)
        if k in out:
            sys.exit(f"{path}:{n}: duplicate {k}")
        out[k] = v
    return out


def esc(v):
    v = v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    v = v.replace("\\n", "\u0000")  # keep \n as Android's newline escape
    v = v.replace("\\", "\\\\").replace("'", "\\'").replace('"', '\\"')
    v = v.replace("\u0000", "\\n")
    if v[:1] in "@?":
        v = "\\" + v
    return v


def xml(d):
    rows = ['<?xml version="1.0" encoding="utf-8"?>', "<!-- Generated from scripts/l10n sources; edit those. -->", "<resources>"]
    for k, v in d.items():
        if k.endswith("[]"):
            rows.append(f'    <string-array name="{k[:-2]}">')
            rows += [f"        <item>{esc(i)}</item>" for i in v.split("|")]
            rows.append("    </string-array>")
        elif k.endswith("!"):
            rows.append(f'    <string name="{k[:-1]}" translatable="false">{esc(v)}</string>')
        else:
            rows.append(f'    <string name="{k}">{esc(v)}</string>')
    rows.append("</resources>")
    return "\n".join(rows) + "\n"


def main():
    errors = []
    langs = set()
    for mod, res in MODULES.items():
        en = parse(os.path.join(HERE, f"{mod}.en.txt"))
        want = {k: v for k, v in en.items() if not k.endswith("!")}
        out = os.path.join(REPO, res, "values", "strings.xml")
        os.makedirs(os.path.dirname(out), exist_ok=True)
        open(out, "w", encoding="utf-8").write(xml(en))
        for src in sorted(glob.glob(os.path.join(HERE, f"{mod}.*.txt"))):
            q = src.split(".")[-2]
            if q == "en":
                continue
            langs.add(q)
            tr = parse(src)
            for k in want.keys() - tr.keys():
                errors.append(f"{mod}.{q}: missing {k}")
            for k in tr.keys() - want.keys():
                errors.append(f"{mod}.{q}: unknown {k}")
            for k in want.keys() & tr.keys():
                if k.endswith("[]"):
                    if len(tr[k].split("|")) != len(want[k].split("|")):
                        errors.append(f"{mod}.{q}: {k} has {len(tr[k].split('|'))} items")
                elif sorted(PH.findall(tr[k])) != sorted(PH.findall(want[k])) or \
                        sorted(m.group() for m in PH.finditer(tr[k])) != sorted(m.group() for m in PH.finditer(want[k])):
                    errors.append(f"{mod}.{q}: {k} placeholders {PH.findall(tr[k])} != {PH.findall(want[k])}")
            d = os.path.join(REPO, res, f"values-{q}")
            os.makedirs(d, exist_ok=True)
            open(os.path.join(d, "strings.xml"), "w", encoding="utf-8").write(xml({k: tr[k] for k in want if k in tr}))
    for e in errors:
        print(e)
    print(f"{len(langs)} languages: {' '.join(sorted(langs))}; {len(errors)} problems")
    sys.exit(1 if errors else 0)


if __name__ == "__main__":
    main()
