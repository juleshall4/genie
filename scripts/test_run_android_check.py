import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


class AndroidCheckExitStatusTest(unittest.TestCase):
    def test_failed_checks_return_failure_even_when_adb_succeeds(self):
        with tempfile.TemporaryDirectory() as directory:
            adb = Path(directory) / "platform-tools" / "adb"
            adb.parent.mkdir()
            adb.write_text("""#!/usr/bin/env python3
import os, sys
if sys.argv[1] == 'devices':
    print('List of devices attached\\nemulator-test device')
elif 'instrument' in sys.argv:
    print(os.environ['CHECK_RESULT'])
else:
    print('null')
""")
            adb.chmod(0o755)
            for result, expected in [("FAIL: recognition returned no words", 1),
                                     ("Failed: recognition returned no words", 1),
                                     ("Skipped: missing account", 1), ("PASS: recognized speech", 0)]:
                with self.subTest(result=result):
                    environment = dict(os.environ, ANDROID_HOME=directory, ANDROID_SERIAL="emulator-test", CHECK_RESULT=result)
                    check = subprocess.run([sys.executable, str(Path(__file__).with_name("run_android_check.py")), "TestRunner"],
                                           env=environment, capture_output=True, text=True)
                    self.assertEqual(expected, check.returncode, check.stdout + check.stderr)


if __name__ == "__main__":
    unittest.main()
