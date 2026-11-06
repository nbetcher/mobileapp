import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "lib"))

from exhaustive_when_fix import Site, apply, parse_sites, snake_case  # noqa: E402

COLLECTOR = """class C {
    fun f(error: UserFacingError) = mapOf(
        "error_type" to when (error) {
            is UserFacingError.FailedToScan -> "failed_to_scan"
            // comment lines are fine
            is UserFacingError.MissingCompanionApp -> "missing_companion_app"
        },
    )
}
"""
WHEN_COL = COLLECTOR.splitlines()[2].index("when") + 1


class SnakeCaseTest(unittest.TestCase):
    def test_camel_case(self):
        self.assertEqual("failed_to_install_language_pack", snake_case("FailedToInstallLanguagePack"))

    def test_acronyms(self):
        self.assertEqual("ble_scan_failed", snake_case("BLEScanFailed"))
        self.assertEqual("failed_to_download_pbw", snake_case("FailedToDownloadPbw"))


class ParseTest(unittest.TestCase):
    def test_parses_and_dedupes_repeated_gradle_errors(self):
        with tempfile.TemporaryDirectory() as root:
            path = os.path.join(root, "a", "X.kt")
            line = (f"e: file://{path}:60:41 'when' expression must be exhaustive. "
                    "Add the 'is FailedToInstallLanguagePack' branch or an 'else' branch.")
            sites = parse_sites(f"{line}\n> Task :x FAILED\n{line}\n", root)
        self.assertEqual([Site("a/X.kt", 60, 41, ("is FailedToInstallLanguagePack",))], sites)

    def test_parses_multiple_missing_branches(self):
        line = ("e: file:///r/X.kt:1:2 'when' expression must be exhaustive. "
                "Add the 'is A', 'is B' branches or an 'else' branch.")
        self.assertEqual(("is A", "is B"), parse_sites(line, "/r")[0].missing)


class ApplyTest(unittest.TestCase):
    def run_fix(self, source, missing, line=3, col=WHEN_COL, allowed=True):
        with tempfile.TemporaryDirectory() as root:
            with open(os.path.join(root, "X.kt"), "w") as f:
                f.write(source)
            result = apply([Site("X.kt", line, col, missing)], {"X.kt"} if allowed else set(), root)
            with open(os.path.join(root, "X.kt")) as f:
                return result, f.read()

    def test_inserts_qualified_branch_after_last_branch(self):
        result, out = self.run_fix(COLLECTOR, ("is FailedToInstallLanguagePack",))
        self.assertEqual([], result.unfixable)
        self.assertIn(
            '            is UserFacingError.MissingCompanionApp -> "missing_companion_app"\n'
            '            is UserFacingError.FailedToInstallLanguagePack -> "failed_to_install_language_pack"\n'
            "        },\n",
            out,
        )

    def test_inserts_several_branches(self):
        result, out = self.run_fix(COLLECTOR, ("is A", "is B"))
        self.assertEqual(2, len(result.added))
        self.assertIn('is UserFacingError.A -> "a"\n            is UserFacingError.B -> "b"\n', out)

    def test_refuses_files_outside_allowlist(self):
        result, out = self.run_fix(COLLECTOR, ("is A",), allowed=False)
        self.assertEqual(COLLECTOR, out)
        self.assertIn("not a fork-owned file", result.unfixable[0])

    def test_refuses_non_type_branches(self):
        result, out = self.run_fix(COLLECTOR, ("NEW_ENUM_VALUE",))
        self.assertEqual(COLLECTOR, out)
        self.assertEqual(1, len(result.unfixable))

    def test_refuses_bodies_that_are_not_pure_string_maps(self):
        source = COLLECTOR.replace('-> "failed_to_scan"', "-> doSomething()")
        result, out = self.run_fix(source, ("is A",))
        self.assertEqual(source, out)
        self.assertIn("not purely", result.unfixable[0])

    def test_refuses_wire_value_collisions(self):
        result, out = self.run_fix(COLLECTOR, ("is FailedToScan",))
        self.assertEqual(COLLECTOR, out)
        self.assertIn("already used", result.unfixable[0])

    def test_refuses_position_not_at_when(self):
        result, out = self.run_fix(COLLECTOR, ("is A",), col=1)
        self.assertEqual(COLLECTOR, out)
        self.assertIn("does not point at", result.unfixable[0])

    def test_braces_in_strings_do_not_confuse_body_detection(self):
        source = COLLECTOR.replace('"failed_to_scan"', '"failed_to_scan"').replace(
            "// comment lines are fine", '// a "}" in a comment'
        )
        result, _ = self.run_fix(source, ("is A",))
        self.assertEqual([], result.unfixable)


if __name__ == "__main__":
    unittest.main()
