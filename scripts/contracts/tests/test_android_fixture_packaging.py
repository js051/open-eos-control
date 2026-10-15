"""Read-only fixture-plan checks; does not stand in for AGP merge execution."""
import hashlib
import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[3]
APP = ROOT / "android/app"
BUILD = APP / "build.gradle.kts"


def fixture_paths(name):
    text = BUILD.read_text()
    block = re.search(rf"val {name} = listOf\((.*?)\n\)", text, re.S).group(1)
    return [(APP / value).resolve() for value in re.findall(r'"([^"\n]+)"', block)]


class AndroidFixturePackagingTest(unittest.TestCase):
    def test_both_explicit_manifests_have_unique_existing_only_json_fixtures(self):
        for name, count in [("unitContractFixtures", 12), ("androidContractFixtures", 2)]:
            paths = fixture_paths(name)
            self.assertEqual(count, len(paths))
            self.assertEqual(len(paths), len({path.name for path in paths}))
            for path in paths:
                self.assertTrue(path.is_relative_to(ROOT / "contracts"))
                self.assertEqual(".json", path.suffix)
                self.assertTrue(path.is_file())
                self.assertEqual(64, len(hashlib.sha256(path.read_bytes()).hexdigest()))
            # Generated flat files must not collide with default source-set assets/resources either.
            defaults = [APP / "src/test/resources"] if name == "unitContractFixtures" else [APP / "src/androidTest/assets"]
            for directory in defaults:
                if directory.exists():
                    existing = {str(path.relative_to(directory)) for path in directory.rglob("*") if path.is_file()}
                    self.assertFalse(existing.intersection(path.name for path in paths))

    def test_existing_flat_resource_and_asset_loader_names_are_preserved(self):
        unit = {path.name for path in fixture_paths("unitContractFixtures")}
        assets = {path.name for path in fixture_paths("androidContractFixtures")}
        for folder, available, pattern in [
            (APP / "src/test", unit, r'getResourceAsStream\("/?([^"/]+\.json)"\)'),
            (APP / "src/androidTest", assets, r'assets\.open\("([^"/]+\.json)"\)'),
        ]:
            for source in folder.rglob("*.kt"):
                for name in re.findall(pattern, source.read_text()):
                    self.assertIn(name, available, str(source))

    def test_sync_tasks_fail_on_duplicates_and_are_prebuild_dependencies(self):
        text = BUILD.read_text()
        self.assertEqual(2, text.count("duplicatesStrategy = DuplicatesStrategy.FAIL"))
        self.assertIn('tasks.named("preBuild") { dependsOn(prepareUnitContractFixtures, prepareAndroidContractFixtures) }', text)
        self.assertNotIn('assets.srcDir("../../contracts/', text)
        self.assertNotIn('resources.srcDir("../../contracts/', text)
        self.assertIn('check(paths.map { file(it).name }.distinct().size == paths.size)', text)
