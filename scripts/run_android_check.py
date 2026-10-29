#!/usr/bin/env python3
"""Run an emulator check and leave the authorized Space shortcut connected."""
import os
import pathlib
import re
import subprocess
import sys
from restore_shortcut import devices, restore

root = pathlib.Path(__file__).resolve().parent.parent
sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
if not sdk:
    for line in (root / "local.properties").read_text().splitlines():
        if line.startswith("sdk.dir="):
            sdk = line.partition("=")[2].replace("\\:", ":").replace("\\\\", "\\")
adb = str(pathlib.Path(sdk) / "platform-tools" / "adb") if sdk else "adb"
if len(sys.argv) < 2:
    sys.exit("Usage: python3 scripts/run_android_check.py RUNNER [-e NAME VALUE ...]")
serials = devices(adb)
serial = os.environ.get("ANDROID_SERIAL")
if not serial:
    if len(serials) != 1:
        sys.exit("Connect one emulator/device, or set ANDROID_SERIAL.")
    serial = serials[0]
runner = sys.argv[1]
if "/" not in runner:
    runner = "dev.genie.android.test/" + runner
try:
    result = subprocess.run([adb, "-s", serial, "shell", "am", "instrument", "-w", *sys.argv[2:], runner],
                            capture_output=True, text=True)
    print(result.stdout, end="")
    if (result.returncode or "Failed:" in result.stdout or "Skipped:" in result.stdout
            or "INSTRUMENTATION_FAILED" in result.stdout
            or re.search(r"INSTRUMENTATION_CODE:\s*(?!-1\b)-?\d+", result.stdout)):
        sys.exit("Android check failed.")
finally:
    restore(adb, serial)
