#!/usr/bin/env python3
"""Drive real Compose accessibility nodes while the disposable emulator records video."""
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET


def adb(*args):
    return subprocess.check_output(["adb", *args], stderr=subprocess.STDOUT, text=True, timeout=30)


if os.environ.get("MYVAULT_DISPOSABLE_EMULATOR") != "1" or not adb("get-serialno").strip().startswith("emulator-"):
    raise SystemExit("Motion recording is allowed only on an explicitly opted-in disposable emulator.")


def hierarchy():
    # /dev/tty may be unavailable to an exec-out session. The same on-device
    # dump path is used by the existing Android 11/14 migration tests.
    path = "/sdcard/myvault-motion-window.xml"
    adb("shell", "uiautomator", "dump", "--compressed", path)
    return ET.fromstring(adb("exec-out", "cat", path))


def tap(value, description=False):
    key = "content-desc" if description else "text"
    deadline = time.monotonic() + 25
    while time.monotonic() < deadline:
        root = hierarchy()
        nodes = list(root.iter("node"))
        matches = [n for n in nodes if n.attrib.get(key, "") == value]
        if not matches:
            matches = [n for n in nodes if value in n.attrib.get(key, "")]
        for node in matches:
            rect = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.attrib.get("bounds", ""))
            if rect:
                x1, y1, x2, y2 = map(int, rect.groups())
                if x2 > x1 and y2 > y1:
                    print(f"Tap {key}: {value}", flush=True)
                    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
                    # Give the platform frame clock time to draw the actual transition.
                    time.sleep(0.7)
                    return
        time.sleep(0.5)
    raise RuntimeError(f"Could not find an on-screen {key} containing {value!r}")


def main():
    adb("shell", "am", "start", "-W", "-n", "com.myvault.app.debug/com.myvault.app.MainActivity")
    time.sleep(1)
    tap("Sample space")
    tap("Cards view", description=True)
    tap("List view", description=True)
    tap("Expand Evidence entry", description=True)
    tap("Collapse Evidence entry", description=True)
    tap("Cards view", description=True)
    tap("Evidence entry")
    tap("Preview", description=True)
    tap("Close preview", description=True)
    tap("Close editor", description=True)
    tap("Settings", description=True)
    tap("Back", description=True)
    tap("Back to spaces", description=True)
    tap("Options for Sample space", description=True)
    tap("Edit space")
    tap("Cancel")
    print("Real activity interaction recording completed.", flush=True)


try:
    main()
except Exception as exc:
    try:
        labels = [
            n.attrib.get("text") or n.attrib.get("content-desc")
            for n in hierarchy().iter("node")
            if n.attrib.get("text") or n.attrib.get("content-desc")
        ]
    except Exception:
        labels = []
    details = f"{type(exc).__name__}: {exc}; visible labels: {' | '.join(labels)}"
    escaped = details[-3000:].replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
    print(f"::error title=Real-activity motion recording failed::{escaped}", flush=True)
    raise SystemExit(1) from exc
