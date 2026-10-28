#!/usr/bin/env python3
"""Migrate a synthetic backup between two packages on an explicitly disposable emulator."""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


def adb(*args, check=True):
    return subprocess.run(["adb", *args], check=check, text=True, capture_output=True, timeout=30)


if os.environ.get("MYVAULT_DISPOSABLE_EMULATOR") != "1" or not adb("get-serialno").stdout.strip().startswith("emulator-"):
    raise SystemExit("This migration test requires an explicitly authorized disposable emulator.")

last_xml = ""
last_root = None
trace = []


def hierarchy():
    global last_xml, last_root
    result = adb("shell", "uiautomator", "dump", "--compressed", "/sdcard/myvault-ci-window.xml", check=False)
    if result.returncode:
        return None
    result = adb("exec-out", "cat", "/sdcard/myvault-ci-window.xml", check=False)
    if result.returncode:
        return None
    last_xml = result.stdout
    try:
        last_root = ET.fromstring(last_xml)
        return last_root
    except ET.ParseError:
        last_root = None
        return None


def find(label, attribute="text", contains=False, timeout=25):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        root = hierarchy()
        if root is not None:
            for node in root.iter("node"):
                value = node.get(attribute, "")
                if (label in value if contains else label == value) and node.get("bounds"):
                    return node
        time.sleep(0.5)
    return None


def click(label, attribute="text", contains=False, timeout=25):
    node = find(label, attribute, contains, timeout)
    if node is None:
        raise AssertionError(f"Could not find {attribute}={label!r}")
    trace.append(f"tap {attribute}={label!r}: package={node.get('package')} text={node.get('text')!r} desc={node.get('content-desc')!r} enabled={node.get('enabled')} clickable={node.get('clickable')} bounds={node.get('bounds')}")
    if node.get("package", "").endswith("documentsui") and label == "myvault-ci" and last_root is not None:
        parents = {child: parent for parent in last_root.iter() for child in parent}
        ancestor = parents.get(node)
        while ancestor is not None and ancestor.tag == "node":
            trace.append(f"ancestor class={ancestor.get('class')} clickable={ancestor.get('clickable')} enabled={ancestor.get('enabled')} bounds={ancestor.get('bounds')}")
            if ancestor.get("clickable") == "true" and ancestor.get("enabled") == "true":
                node = ancestor
                break
            ancestor = parents.get(ancestor)
    bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
    if not bounds:
        raise AssertionError(f"Invalid bounds for {label!r}")
    x1, y1, x2, y2 = map(int, bounds.groups())
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


def main():
    adb("shell", "am", "start", "-n", "com.myvault.app.preview/com.myvault.app.MainActivity")
    click("Settings", "content-desc")
    click("Import / Export")
    if find("Choose JSON file", timeout=5) is None:
        size = re.findall(r"(\d+)x(\d+)", adb("shell", "wm", "size").stdout)
        if not size:
            raise AssertionError("Could not read emulator display size for scrolling.")
        width, height = map(int, size[-1])
        adb("shell", "input", "swipe", str(width // 2), str(height * 3 // 4), str(width // 2), str(height // 3), "400")
    click("Choose JSON file")
    # Some DocumentsUI versions visually truncate the displayed filename. A stable prefix
    # still identifies this unique, synthetic test document without relying on screen size.
    name = "myvault-ci"
    if find(name, contains=True, timeout=8) is None:
        click("Show roots", "content-desc")
        click("Downloads")
    # On some pickers the Downloads subtitle is part of the document row, so the prior tap
    # may already have selected the document and opened the app's confirmation dialog.
    if find("Import backup?", timeout=4) is None:
        click(name, contains=True)
        # Older document pickers may first select the row rather than opening it.
        if find("Import backup?", timeout=8) is None:
            if find("Open", timeout=3) is not None:
                click("Open")
            else:
                click(name, contains=True)
        # Android 11's DocumentsUI exposes file activation through an accessibility action
        # on its RecyclerView row even when raw taps do not activate it in the emulator.
        if find("Import backup?", timeout=8) is None:
            result = adb(
                "shell", "am", "instrument", "-w", "-r", "-e", "class",
                "com.myvault.app.ui.DocumentPickerActionTest", "-e", "myvault.pickerPhase", "select",
                "com.myvault.app.debug.test/androidx.test.runner.AndroidJUnitRunner", check=False,
            )
            trace.append(f"picker accessibility action: exit={result.returncode} output={result.stdout[-1200:]}")
            if result.returncode or "OK (1 test)" not in result.stdout:
                raise AssertionError("The system picker could not activate the synthetic document.")
    if find("Import backup?", timeout=25) is None:
        raise AssertionError("The preview did not show the backup confirmation.")
    click("Also restore appearance settings", "content-desc")
    click("Import")
    # Once the confirmation is dismissed, close settings and wait for the imported space.
    time.sleep(1)
    click("Back", "content-desc")
    click("Offline space", timeout=40)
    click("Offline note")
    if find("Saved without a network.", contains=True) is None:
        raise AssertionError("The imported entry body was not visible.")
    if find("Remove Offline tag tag", "content-desc") is None:
        raise AssertionError("The imported entry tag was not visible.")
    if find("Entry cover", "content-desc") is None:
        raise AssertionError("The imported private cover was not visible.")
    adb("shell", "am", "force-stop", "com.myvault.app.preview")
    adb("shell", "am", "start", "-n", "com.myvault.app.preview/com.myvault.app.MainActivity")
    click("Offline space", timeout=40)
    click("Offline note")
    if find("Saved without a network.", contains=True) is None:
        raise AssertionError("The imported entry did not survive a preview restart.")
    print("::notice title=Side-by-side backup migration::The preview imported the debug app's synthetic backup, including its note, tag and private cover, and retained it after restart.", flush=True)


try:
    main()
except Exception as exc:
    try:
        root = ET.fromstring(last_xml)
        visible = [
            f"{node.get('package')}:{node.get('text') or node.get('content-desc')}"
            for node in root.iter("node")
            if node.get("text") or node.get("content-desc")
        ]
    except ET.ParseError:
        visible = []
    details = f"{type(exc).__name__}: {exc}\nActions: {'; '.join(trace)}\nVisible: {' | '.join(visible)}"
    escaped = details[-4000:].replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
    print(f"::error title=Preview migration test failed::{escaped}", file=sys.stderr, flush=True)
    raise SystemExit(1) from exc
