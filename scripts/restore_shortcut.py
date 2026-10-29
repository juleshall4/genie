#!/usr/bin/env python3
"""Restore an already-authorized shortcut after adb installs or instrumentation."""
import subprocess
import sys
import time

COMPONENT = "dev.genie.android/dev.genie.android.GenieShortcutService"


def shell(adb, serial, *args):
    return subprocess.check_output([adb, "-s", serial, "shell", *args], text=True).strip()


def connected(adb, serial):
    state = shell(adb, serial, "dumpsys", "accessibility")
    bound = state.split("Bound services:", 1)[-1].split("Enabled services:", 1)[0]
    return "Genie Space shortcut" in bound


def restore(adb, serial):
    enabled = shell(adb, serial, "settings", "get", "secure", "enabled_accessibility_services")
    services = enabled.split(":") if enabled not in ("null", "") else []
    if COMPONENT not in services:
        print("Space shortcut is off; leaving it off.")
        return
    if not connected(adb, serial):
        others = [service for service in services if service != COMPONENT]
        if others:
            shell(adb, serial, "settings", "put", "secure", "enabled_accessibility_services", ":".join(others))
        else:
            shell(adb, serial, "settings", "delete", "secure", "enabled_accessibility_services")
        # Observe removal before re-adding: Android processes settings changes asynchronously.
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            if COMPONENT not in shell(adb, serial, "dumpsys", "accessibility").split("Enabled services:", 1)[-1].splitlines()[0]:
                break
            time.sleep(0.1)
        shell(adb, serial, "settings", "put", "secure", "enabled_accessibility_services", enabled)
        shell(adb, serial, "settings", "put", "secure", "accessibility_enabled", "1")
        deadline = time.monotonic() + 10
        while not connected(adb, serial):
            if time.monotonic() >= deadline:
                raise RuntimeError("Space shortcut did not reconnect")
            time.sleep(0.1)
    print("Space shortcut is connected.")


def devices(adb):
    output = subprocess.check_output([adb, "devices"], text=True)
    return [line.split()[0] for line in output.splitlines()[1:]
            if len(line.split()) == 2 and line.split()[1] == "device"]


if __name__ == "__main__":
    try:
        for serial in devices(sys.argv[1]):
            restore(sys.argv[1], serial)
    except (subprocess.CalledProcessError, RuntimeError) as error:
        sys.exit(f"Shortcut recovery failed: {error}")
