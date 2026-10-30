import unittest
from unittest.mock import patch

import restore_shortcut as shortcut


def state(bound="", enabled="", binding="", crashed=""):
    return (f"Bound services:{{{bound}}}\nEnabled services:{{{enabled}}}\n"
            f"Binding services:{{{binding}}}\nCrashed services:{{{crashed}}}\n")


class ShortcutRecoveryTest(unittest.TestCase):
    def test_connected_service_on_second_line_is_healthy(self):
        output = state(
            bound="Service[label=Rotation Control],\n                     Service[label=Genie shortcut]",
            enabled="{example/.Reader}, {" + shortcut.COMPONENT + "}",
        )
        with patch.object(shortcut, "shell", return_value=output):
            self.assertTrue(shortcut.connected("adb", "phone"))
            self.assertFalse(shortcut.detached("adb", "phone"))

    def test_bound_but_crashed_service_is_not_healthy(self):
        output = state(bound="Service[label=Genie shortcut]", crashed=shortcut.COMPONENT)
        with patch.object(shortcut, "shell", return_value=output):
            self.assertFalse(shortcut.connected("adb", "emulator"))

    def test_waits_for_pending_binding_to_detach_and_preserves_other_services(self):
        original = shortcut.COMPONENT + ":example/.Reader"
        phase = "initial"
        polls = 0
        detached = False

        def shell(adb, serial, *args):
            nonlocal phase, polls, detached
            if args == ("settings", "get", "secure", "enabled_accessibility_services"):
                return original
            if args == ("dumpsys", "accessibility"):
                if phase == "initial":
                    return state(enabled=original, binding=shortcut.COMPONENT)
                if phase == "disabled":
                    polls += 1
                    detached = polls >= 3
                    return state(enabled="example/.Reader", binding="" if detached else shortcut.COMPONENT)
                return state(bound="Service[label=Genie shortcut]", enabled=original)
            if args[:4] == ("settings", "put", "secure", "enabled_accessibility_services"):
                if args[4] == original:
                    self.assertTrue(detached, "Re-enabled the shortcut while its previous binding was still present")
                    phase = "restored"
                else:
                    self.assertEqual("example/.Reader", args[4])
                    phase = "disabled"
            return ""

        with patch.object(shortcut, "shell", side_effect=shell), patch.object(shortcut.time, "sleep"):
            shortcut.restore("adb", "emulator")
        self.assertEqual("restored", phase)

    def test_disabled_shortcut_is_left_disabled(self):
        with patch.object(shortcut, "shell", return_value="example/.Reader") as shell:
            shortcut.restore("adb", "emulator")
        self.assertEqual(1, shell.call_count)

    def test_disconnect_timeout_restores_the_users_enabled_services(self):
        original = shortcut.COMPONENT + ":example/.Reader"
        writes = []

        def shell(adb, serial, *args):
            if args == ("settings", "get", "secure", "enabled_accessibility_services"):
                return original
            if args == ("dumpsys", "accessibility"):
                return state(binding=shortcut.COMPONENT)
            writes.append(args)
            return ""

        with (patch.object(shortcut, "shell", side_effect=shell),
              patch.object(shortcut.time, "monotonic", side_effect=[0, 6])):
            with self.assertRaisesRegex(RuntimeError, "did not fully disconnect"):
                shortcut.restore("adb", "emulator")
        self.assertIn(("settings", "put", "secure", "enabled_accessibility_services", original), writes)
        self.assertIn(("settings", "put", "secure", "accessibility_enabled", "1"), writes)

    def test_reconnects_if_the_old_binding_dies_after_install(self):
        writes = []

        def shell(adb, serial, *args):
            if args == ("settings", "get", "secure", "enabled_accessibility_services"):
                return shortcut.COMPONENT
            if args == ("dumpsys", "accessibility"):
                return state()
            writes.append(args)
            return ""

        with (patch.object(shortcut, "shell", side_effect=shell),
              patch.object(shortcut, "connected", side_effect=[True, True, False, False, True, True]),
              patch.object(shortcut.time, "sleep")):
            shortcut.restore("adb", "emulator")
        self.assertIn(("settings", "delete", "secure", "enabled_accessibility_services"), writes)
        self.assertIn(("settings", "put", "secure", "enabled_accessibility_services", shortcut.COMPONENT), writes)


if __name__ == "__main__":
    unittest.main()
