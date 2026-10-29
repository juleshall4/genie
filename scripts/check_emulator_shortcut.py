#!/usr/bin/env python3
"""Exercise real Space key events without reconnecting the accessibility service."""
import argparse
import os
from pathlib import Path
import re
import subprocess
import sys
import time

from restore_shortcut import connected, devices, shell


def check(adb, serial, cycles):
    if not serial.startswith("emulator-"):
        raise RuntimeError("This check requires an emulator with root sendevent access.")
    if not connected(adb, serial):
        raise RuntimeError("Enable the Space shortcut before running this check.")
    keyboard = re.search(r"qwerty2[^\n]*\n(?:[^\n]*\n)*?\s+Path: (/dev/input/event[0-9]+)",
                         shell(adb, serial, "dumpsys", "input"))
    if not keyboard:
        raise RuntimeError("The emulator's qwerty2 keyboard was not found.")

    def key(code):
        # adb input keyevent bypasses accessibility filtering. Use the keyboard device instead.
        for kind, key_code, value in ((1, code, 1), (0, 0, 0), (1, code, 0), (0, 0, 0)):
            shell(adb, serial, "su", "0", "sendevent", keyboard[1], str(kind), str(key_code), str(value))

    def visible():
        return bool(re.search(r"Window\{[^}\n]*\bGenie\}", shell(adb, serial, "dumpsys", "window")))

    def wait_visible(wanted):
        deadline = time.monotonic() + 6
        while time.monotonic() < deadline:
            if not connected(adb, serial):
                raise RuntimeError("The shortcut service crashed or disconnected.")
            if visible() == wanted:
                return
            time.sleep(0.1)
        raise RuntimeError("Space did not open Genie." if wanted else "The key did not dismiss Genie.")

    if visible():
        raise RuntimeError("Dismiss Genie before running this check.")
    for cycle in range(cycles):
        if cycle % 5 == 0:
            shell(adb, serial, "am", "start", "-W", "-n", "dev.genie.android/.MainActivity")
            shell(adb, serial, "input", "keyevent", "KEYCODE_HOME")
        key(57)  # Linux KEY_SPACE
        wait_visible(True)
        key(57 if cycle % 2 == 0 else 1)  # Alternate Space and Escape dismissal.
        wait_visible(False)
    print(f"PASS: {cycles} Space opens and dismissals, including returns from settings; no reconnects.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cycles", type=int, default=20)
    args = parser.parse_args()
    if args.cycles < 1:
        parser.error("--cycles must be positive")
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        properties = Path(__file__).resolve().parent.parent / "local.properties"
        if properties.exists():
            sdk = next((line.partition("=")[2] for line in properties.read_text().splitlines()
                        if line.startswith("sdk.dir=")), None)
    adb = str(Path(sdk) / "platform-tools" / "adb") if sdk else "adb"
    try:
        serials = devices(adb)
        serial = os.environ.get("ANDROID_SERIAL")
        if not serial:
            if len(serials) != 1:
                raise RuntimeError("Connect one emulator, or set ANDROID_SERIAL.")
            serial = serials[0]
        check(adb, serial, args.cycles)
    except (subprocess.CalledProcessError, RuntimeError) as error:
        sys.exit(f"FAIL: {error}")
