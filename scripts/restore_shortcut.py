#!/usr/bin/env python3
"""Restore an already-authorized shortcut after adb installs or instrumentation."""
import subprocess
import sys
import time

COMPONENT = "dev.genie.android/dev.genie.android.GenieShortcutService"


def shell(adb, serial, *args):
    return subprocess.check_output([adb, "-s", serial, "shell", *args], text=True).strip()


def section(state, name):
    return state.partition(name + ":")[2].split("\n", 1)[0]


def connected(adb, serial):
    state = shell(adb, serial, "dumpsys", "accessibility")
    return ("Genie Space shortcut" in section(state, "Bound services")
            and COMPONENT not in section(state, "Crashed services"))


def detached(adb, serial):
    state = shell(adb, serial, "dumpsys", "accessibility")
    return ("Genie Space shortcut" not in section(state, "Bound services")
            and all(COMPONENT not in section(state, name)
                    for name in ("Enabled services", "Binding services", "Crashed services")))


def restore(adb, serial):
    enabled = shell(adb, serial, "settings", "get", "secure", "enabled_accessibility_services")
    services = enabled.split(":") if enabled not in ("null", "") else []
    if COMPONENT not in services:
        print("Space shortcut is off; leaving it off.")
        return
    for _ in range(3):
        if not connected(adb, serial):
            others = [service for service in services if service != COMPONENT]
            if others:
                shell(adb, serial, "settings", "put", "secure", "enabled_accessibility_services", ":".join(others))
            else:
                shell(adb, serial, "settings", "delete", "secure", "enabled_accessibility_services")
            try:
                deadline = time.monotonic() + 5
                while not detached(adb, serial):
                    if time.monotonic() >= deadline:
                        raise RuntimeError("Space shortcut did not fully disconnect")
                    time.sleep(0.1)
            finally:
                shell(adb, serial, "settings", "put", "secure", "enabled_accessibility_services", enabled)
                shell(adb, serial, "settings", "put", "secure", "accessibility_enabled", "1")
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            if connected(adb, serial):
                # Package replacement can report the old binding before its binder dies.
                time.sleep(0.5)
                if connected(adb, serial):
                    print("Space shortcut is connected.")
                    return
                break
            state = shell(adb, serial, "dumpsys", "accessibility")
            if COMPONENT in section(state, "Crashed services"):
                break
            time.sleep(0.1)
    raise RuntimeError("Space shortcut did not reconnect")


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
