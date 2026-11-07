#!/usr/bin/env python3
"""Verify util/EmojiData.kt against emoji-test.txt and the audit report.json."""
import json
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent.parent
DL = Path(__file__).parent / "data"
REPORT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path.home() / (
    ".claude/projects/C--Users-Mr-Music-Documents-GitHub-iokey/overhaul/look/study-work/emoji/out/report.json"
)

TONES = {0x1F3FB, 0x1F3FC, 0x1F3FD, 0x1F3FE, 0x1F3FF}
STR = r'"((?:[^"\\]|\\.)*)"'
line_re = re.compile(r"^([0-9A-F ]+?)\s*;\s*([a-z-]+)\s*#")

ref = []
for raw in (DL / "emoji-test.txt").read_text(encoding="utf-8").splitlines():
    m = line_re.match(raw)
    if not m or m.group(2) != "fully-qualified":
        continue
    s = "".join(chr(int(x, 16)) for x in m.group(1).split())
    if any(ord(c) in TONES for c in s):
        continue
    ref.append(s)

kt = (REPO / "app/src/main/java/com/localboard/keyboard/util/EmojiData.kt").read_text(encoding="utf-8")
body = kt[:kt.index("val wordToEmoji")]

counts = {}
all_e = []
for m in re.finditer(STR + r'\s+to\s+listOf\(', body):
    start = m.end()
    depth, i, instr = 1, m.end(), False
    while depth:
        c = body[i]
        if c == '"':
            instr = not instr
        elif not instr:
            if c == "(":
                depth += 1
            elif c == ")":
                depth -= 1
        i += 1
    emo = re.findall(STR, body[start:i - 1])
    counts[m.group(1)] = len(emo)
    all_e.extend(emo)

print("entries:", len(all_e), "distinct:", len(set(all_e)))
print("order-exact vs emoji-test.txt palette:", all_e == ref)
if all_e != ref:
    for i, (a, b) in enumerate(zip(all_e, ref)):
        if a != b:
            print("first divergence at", i, repr(a), "vs", repr(b))
            break

rep = json.loads(REPORT.read_text(encoding="utf-8"))
ok = True
for g, n in rep["unicode"]["groups"].items():
    got = counts.get(g)
    flag = "OK" if got == n else "MISMATCH"
    ok = ok and got == n
    print(f"  {g}: file {got} vs report {n} {flag}")
print("per-category:", "ALL MATCH" if ok else "MISMATCH")
sys.exit(0 if (ok and all_e == ref and len(all_e) == len(set(all_e))) else 1)
