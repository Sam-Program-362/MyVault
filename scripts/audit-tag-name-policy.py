#!/usr/bin/env python3
"""Read-only diagnostic of the current SQLite guard versus proposed vectors.

Does not test the Android app or PostgreSQL and does not modify a user database.
Python's Unicode tables vary by runtime; this intentionally exercises only the
stable fixture characters, not full Unicode 15.1 conformance.
"""
import json
import sqlite3
import unicodedata
from pathlib import Path

fixture = json.loads((Path(__file__).parent.parent / "tests/fixtures/tag-name-policy-vectors.json").read_text(encoding="utf-8"))
# Explicit Unicode 15.1 White_Space property (not Python's broader isspace).
white_space = set("\u0009\u000a\u000b\u000c\u000d\u0020\u0085\u00a0\u1680\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009\u200a\u2028\u2029\u202f\u205f\u3000")
def reference(value):
    collapsed = " ".join("".join(" " if char in white_space else char for char in value).split(" "))
    collapsed = " ".join(part for part in collapsed.split(" ") if part)
    return unicodedata.normalize("NFC", unicodedata.normalize("NFC", collapsed).casefold())

connection = sqlite3.connect(":memory:")
mismatches = []
for pair in fixture["pairs"]:
    actual = reference(pair["left"]) == reference(pair["right"])
    assert actual == pair["equal"], f"Reference vector mismatch: {pair!r}"
    current = bool(connection.execute("SELECT lower(?) = lower(?)", (pair["left"], pair["right"])).fetchone()[0])
    if current != pair["equal"]:
        mismatches.append(pair)
print(f"Proposal fixture: {len(fixture['pairs'])} vectors agree with the reference on this runtime (Unicode {unicodedata.unidata_version}).")
print(f"Current SQLite lower() disagrees with {len(mismatches)} vector(s); this is a known unresolved integrity gap:")
for pair in mismatches:
    print(f"  {pair['left']!r} vs {pair['right']!r}: expected equal={pair['equal']}")
