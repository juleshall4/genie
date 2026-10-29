#!/usr/bin/env python3
"""Copy a local development key into Genie's private debug-app storage."""
import pathlib
import subprocess
import sys

root = pathlib.Path(__file__).resolve().parent.parent
path = root / ".env"
key = ""
if path.exists():
    for line in path.read_text().splitlines():
        name, separator, value = line.strip().removeprefix("export ").partition("=")
        if separator and name.strip() == "GOOGLE_CLOUD_TTS_API_KEY":
            key = value.strip().strip("\"'")
if not key:
    print("Chirp: add GOOGLE_CLOUD_TTS_API_KEY to .env, then run installDebug again.")
    sys.exit(0)
if len(key) > 4096 or any(character.isspace() for character in key):
    sys.exit("Chirp: invalid API key format in .env; no key was copied.")
adb = sys.argv[1] if len(sys.argv) > 1 else "adb"
try:
    devices = subprocess.run([adb, "devices"], check=True, capture_output=True, text=True)
    serials = [line.split()[0] for line in devices.stdout.splitlines()[1:]
               if len(line.split()) == 2 and line.split()[1] == "device"]
    if not serials:
        sys.exit("Chirp: no connected device; key was not copied.")
    for serial in serials:
        subprocess.run([adb, "-s", serial, "shell", "run-as", "dev.genie.android", "sh", "-c",
                        "'umask 077; mkdir -p no_backup; cat > no_backup/chirp-api-key.pending'"],
                       input=key.encode(), check=True, capture_output=True)
    print("Chirp: key copied privately. Open Genie to encrypt and import it.")
except subprocess.CalledProcessError:
    sys.exit("Chirp: key provisioning failed. Check that the debug app is installed and adb can access it.")
