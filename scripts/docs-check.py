# Checks that the source stays described: every Kotlin file opens with a /* ... */ paragraph
# saying what it is for, and every class, object, interface and function has a comment right
# above it (a /** ... */ line, or // for small private helpers). Run by CI on every push; prints
# what's missing and fails if anything is.
#
#   python3 scripts/docs-check.py

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/java"
DECL = re.compile(
    r"^\s*(?:(?:private|internal|public|protected|override|suspend|inline|operator|infix|"
    r"abstract|open|data|enum|sealed|value|tailrec|@Composable)\s+)*"
    r"(fun|class|object|interface)\b"
)

missing = []
for path in sorted(ROOT.rglob("*.kt")):
    lines = path.read_text(encoding="utf-8").split("\n")
    name = path.relative_to(ROOT.parent.parent.parent.parent)
    if not lines[0].startswith("/*"):
        missing.append(f"{name}:1: no description at the top of the file")
    for i, line in enumerate(lines):
        if not DECL.match(line) or "companion object" in line:
            continue
        j = i - 1
        while j >= 0 and lines[j].strip().startswith("@"):
            j -= 1
        above = lines[j].strip() if j >= 0 else ""
        if not (above.endswith("*/") or above.startswith("//")):
            missing.append(f"{name}:{i + 1}: {line.strip()[:100]}")

if missing:
    print("These need a plain-language description (see CONTRIBUTING.md):")
    print("\n".join(missing))
    sys.exit(1)
print("Every file, class and function is described.")
