#!/usr/bin/env python3
"""Renders a markdown coverage summary from JaCoCo XML reports.

Usage: coverage-summary.py <gated.xml> [full.xml] [--gate-min 95]
Intended for GitHub Actions: append output to $GITHUB_STEP_SUMMARY.
"""
import sys
import xml.etree.ElementTree as ET

GATE_MIN = 95.0
if "--gate-min" in sys.argv:
    GATE_MIN = float(sys.argv[sys.argv.index("--gate-min") + 1])
    argv = [a for a in sys.argv if a != "--gate-min" and a != str(GATE_MIN)]
else:
    argv = sys.argv

gated_xml = argv[1]
full_xml = argv[2] if len(argv) > 2 else None


def pct(covered, missed):
    total = covered + missed
    return 100.0 * covered / total if total else 100.0


def badge(p):
    if p >= GATE_MIN:
        return "✅"
    if p >= 80.0:
        return "🟡"
    return "🔴"


def counters(el):
    out = {}
    for c in el.findall("counter"):
        out[c.get("type")] = (int(c.get("covered")), int(c.get("missed")))
    return out


def bundle_line(el):
    c = counters(el)
    cov, mis = c.get("LINE", (0, 0))
    return cov, mis, pct(cov, mis)


print("## 📊 Unit-test coverage")
print()
gc, gm, gp = bundle_line(ET.parse(gated_xml).getroot())
status = "✅ passes" if gp >= GATE_MIN else "❌ FAILS"
print(f"**Gated scope (enforced):** {gc}/{gc + gm} lines — **{gp:.1f}%** — "
      f"gate ≥ {GATE_MIN:.0f}% {status}")
print()

if full_xml:
    fc, fm, fp = bundle_line(ET.parse(full_xml).getroot())
    print(f"*Whole-repo scope incl. device-verified platform glue:* "
          f"{fc}/{fc + fm} — {fp:.1f}%")
    print()

rows = []
for pkg in ET.parse(gated_xml).getroot().iter("package"):
    cov, mis = counters(pkg).get("LINE", (0, 0))
    if cov + mis:
        rows.append((pkg.get("name"), cov, mis))
rows.sort(key=lambda r: pct(r[1], r[2]))

print("| Package | Lines | Covered | % | |")
print("|---|---|---|---|---|")
for name, cov, mis in rows:
    p_ = pct(cov, mis)
    short = name.replace("com.icegood.findmeinwood.", "").replace("/", ".")
    print(f"| `{short}` | {cov + mis} | {cov} | {p_:.1f}% | {badge(p_)} |")
print()
print("<sub>Browsable HTML: GitHub Pages → https://icegood.github.io/find_me_in_wood/ "
      "(“full” subfolder = whole repo incl. glue) · artifact `coverage-html`.</sub>")
