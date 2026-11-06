import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "lib"))

from exhaustive_when_fix import run, sealed_subtypes, snake_case  # noqa: E402

SEALED = """package lib

sealed class UserFacingError {
    abstract val message: String

    data class FailedToScan(override val message: String) : UserFacingError()
    data class FailedToInstallLanguagePack(override val message: String) : UserFacingError()
    data class MissingCompanionApp(
        override val message: String,
        val appName: String,
    ) : UserFacingError()
    class Helper(val e: UserFacingError) : Other()
    companion object {
        class NotASubtype : UserFacingError()
    }
}
"""

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

ADDED = (
    '            is UserFacingError.MissingCompanionApp -> "missing_companion_app"\n'
    '            is UserFacingError.FailedToInstallLanguagePack -> "failed_to_install_language_pack"\n'
    "        },\n"
)


class SnakeCaseTest(unittest.TestCase):
    def test_camel_case(self):
        self.assertEqual("failed_to_install_language_pack", snake_case("FailedToInstallLanguagePack"))

    def test_acronyms(self):
        self.assertEqual("ble_scan_failed", snake_case("BLEScanFailed"))
        self.assertEqual("failed_to_download_pbw", snake_case("FailedToDownloadPbw"))


class SealedIndexTest(unittest.TestCase):
    def test_direct_nested_subtypes_only(self):
        self.assertEqual(
            {"UserFacingError": ["FailedToScan", "FailedToInstallLanguagePack", "MissingCompanionApp"]},
            sealed_subtypes(SEALED),
        )

    def test_sealed_interface_and_objects(self):
        source = "sealed interface S {\n    data object A : S\n    object B : S, Other\n}\n"
        self.assertEqual({"S": ["A", "B"]}, sealed_subtypes(source))

    def test_braces_in_strings_and_comments_are_ignored(self):
        source = 'sealed class S {\n    // }\n    val x = "}"\n    class A : S()\n}\n'
        self.assertEqual({"S": ["A"]}, sealed_subtypes(source))


class RunTest(unittest.TestCase):
    def run_fix(self, collector, sealed=(SEALED,), allowed=True):
        with tempfile.TemporaryDirectory() as root:
            files = [(f"Sealed{i}.kt", text) for i, text in enumerate(sealed)] + [("X.kt", collector)]
            for name, text in files:
                with open(os.path.join(root, name), "w") as f:
                    f.write(text)
            result = run(root, {"X.kt"} if allowed else set())
            with open(os.path.join(root, "X.kt")) as f:
                return result, f.read()

    def test_inserts_missing_subtype_after_last_branch(self):
        result, out = self.run_fix(COLLECTOR)
        self.assertEqual([], result.unfixable)
        self.assertEqual(1, len(result.added))
        self.assertIn(ADDED, out)

    def test_is_idempotent(self):
        _, once = self.run_fix(COLLECTOR)
        result, twice = self.run_fix(once)
        self.assertEqual([], result.added)
        self.assertEqual(once, twice)

    def test_leaves_files_outside_allowlist(self):
        result, out = self.run_fix(COLLECTOR, allowed=False)
        self.assertEqual(COLLECTOR, out)
        self.assertEqual([], result.added)

    def test_leaves_bodies_that_are_not_pure_string_maps(self):
        source = COLLECTOR.replace('-> "failed_to_scan"', "-> doSomething()")
        result, out = self.run_fix(source)
        self.assertEqual((source, []), (out, result.added))

    def test_leaves_when_with_else(self):
        source = COLLECTOR.replace("            // comment lines are fine\n", '            else -> "other"\n')
        result, out = self.run_fix(source)
        self.assertEqual((source, []), (out, result.added))

    def test_leaves_unqualified_branches(self):
        source = COLLECTOR.replace("is UserFacingError.", "is ")
        result, out = self.run_fix(source)
        self.assertEqual((source, []), (out, result.added))

    def test_leaves_when_naming_types_outside_the_sealed_class(self):
        source = COLLECTOR.replace("UserFacingError.FailedToScan", "UserFacingError.Unknown")
        result, out = self.run_fix(source)
        self.assertEqual((source, []), (out, result.added))

    def test_ambiguous_sealed_names_are_left_alone(self):
        result, out = self.run_fix(COLLECTOR, sealed=(SEALED, SEALED.replace("package lib", "package other")))
        self.assertEqual((COLLECTOR, []), (out, result.added))

    def test_reports_wire_value_collision(self):
        source = COLLECTOR.replace('"missing_companion_app"', '"failed_to_install_language_pack"')
        result, out = self.run_fix(source)
        self.assertEqual(source, out)
        self.assertIn("already used", result.unfixable[0])


if __name__ == "__main__":
    unittest.main()
