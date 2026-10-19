import importlib.util
from pathlib import Path
import plistlib
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location('picker_plist', Path(__file__).parents[1] / 'prepare_ios_picker_test_plist.py')
mod = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(mod)


class PickerPlistTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        root = Path(self.directory.name)
        self.source, self.output, self.receipt, self.ordinary, self.test = [root / n for n in ('source.plist', 'test.plist', 'receipt.json', 'ordinary.plist', 'built.plist')]
        self.original = plistlib.dumps({'CFBundleIdentifier': 'dev.openeos.control', 'OriginalValue': 'retained'})
        self.source.write_bytes(self.original)
        self.ordinary.write_bytes(self.original)

    def prepare(self):
        mod.prepare(self.source, self.output, self.receipt)
        self.test.write_bytes(self.output.read_bytes())

    def test_separate_test_copy_preserves_source_and_unrelated_values(self):
        self.prepare()
        self.assertEqual(self.source.read_bytes(), self.original)
        data = plistlib.loads(self.output.read_bytes())
        self.assertEqual(data['OriginalValue'], 'retained')
        for key in mod.KEYS:
            self.assertIs(data[key], True)
        mod.verify(self.source, self.output, self.receipt, self.ordinary, self.test)

    def test_rejects_source_replacement_existing_output_and_symlink(self):
        with self.assertRaises(ValueError):
            mod.prepare(self.source, self.source, self.receipt)
        self.output.symlink_to(self.source)
        with self.assertRaises(ValueError):
            mod.prepare(self.source, self.output, self.receipt)
        self.assertEqual(self.source.read_bytes(), self.original)

    def test_rejects_dangling_output_symlink_without_creating_target(self):
        target = self.output.parent / 'missing-output'
        self.output.symlink_to(target)
        with self.assertRaises(FileExistsError):
            mod.prepare(self.source, self.output, self.receipt)
        self.assertFalse(target.exists())
        self.assertEqual(self.source.read_bytes(), self.original)

    def test_rejects_dangling_receipt_symlink_without_creating_target(self):
        target = self.receipt.parent / 'missing-receipt'
        self.receipt.symlink_to(target)
        with self.assertRaises(FileExistsError):
            mod.prepare(self.source, self.output, self.receipt)
        self.assertFalse(target.exists())
        self.assertEqual(self.source.read_bytes(), self.original)

    def test_rejects_same_output_and_receipt(self):
        with self.assertRaises(ValueError):
            mod.prepare(self.source, self.output, self.output)
        self.assertFalse(self.output.exists())
        self.assertEqual(self.source.read_bytes(), self.original)

    def test_verification_rejects_ordinary_visibility_and_source_drift(self):
        self.prepare()
        self.ordinary.write_bytes(self.output.read_bytes())
        with self.assertRaises(ValueError):
            mod.verify(self.source, self.output, self.receipt, self.ordinary, self.test)
        self.ordinary.write_bytes(self.original)
        self.source.write_bytes(self.original + b'\n')
        with self.assertRaises(ValueError):
            mod.verify(self.source, self.output, self.receipt, self.ordinary, self.test)

    def test_verification_rejects_test_missing_keys_and_wrong_identity(self):
        self.prepare()
        for data in ({'CFBundleIdentifier': 'dev.openeos.control'},
                     dict(CFBundleIdentifier='other', **{key: True for key in mod.KEYS})):
            self.test.write_bytes(plistlib.dumps(data))
            with self.assertRaises(ValueError):
                mod.verify(self.source, self.output, self.receipt, self.ordinary, self.test)

    def test_ci_override_is_app_specific_and_test_derived_data_is_separate(self):
        root = Path(__file__).parents[3]
        project = (root / 'ios/OpenEOSControl/project.yml').read_text()
        self.assertIn('OEC_APP_INFOPLIST_FILE: App/Info.plist', project)
        app = project.split('  OpenEOSControlTests:', 1)[0]
        self.assertIn('      - path: App\n        excludes:\n          - Info.plist', app)
        self.assertEqual(project.count('INFOPLIST_FILE: $(OEC_APP_INFOPLIST_FILE)'), 1)
        workflow = (root / '.github/workflows/apple-dev.yml').read_text()
        ordinary, testing = workflow.split('- name: Test iOS app and UI', 1)
        self.assertNotIn('OEC_APP_INFOPLIST_FILE=', ordinary)
        self.assertIn('-derivedDataPath .derived-data-ui', testing)
        self.assertIn('"OEC_APP_INFOPLIST_FILE=$RUNNER_TEMP/eos-ui-test.plist"', testing)
        self.assertIn('- name: Verify isolated picker test configuration\n        if: always()', testing)
        source = plistlib.loads((root / 'ios/OpenEOSControl/App/Info.plist').read_bytes())
        for key in mod.KEYS:
            self.assertNotIn(key, source)
