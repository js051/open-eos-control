"""Localized restore-unavailable wiring only; production lifecycle runs in the JVM host suite."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
MAIN = ROOT / "android/app/src/main"

class AndroidMaintenanceRestoreTokenSourceTests(unittest.TestCase):
    def test_both_supported_locales_explain_unavailable_owned_restore(self):
        texts = []
        for folder in ("values", "values-zh-rTW"):
            root = ET.parse(MAIN / "res" / folder / "strings.xml").getroot()
            nodes = root.findall("string[@name='maintenance_restore_bridge_unavailable']")
            self.assertEqual(1, len(nodes))
            self.assertTrue(nodes[0].text)
            texts.append(nodes[0].text)
        self.assertIn("Update Bridge", texts[0])
        self.assertIn("更新 Bridge", texts[1])
        self.assertNotEqual(texts[0], texts[1])

    def test_typed_restore_receipt_selects_localized_resource(self):
        screen = (MAIN / "java/dev/openeos/control/ui/CameraControlScreen.kt").read_text()
        self.assertIn("MaintenanceRestoreEvidence.BRIDGE_UNAVAILABLE -> R.string.maintenance_restore_bridge_unavailable", screen)
