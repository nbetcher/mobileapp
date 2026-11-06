import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "lib"))

from release_changes import (  # noqa: E402
    DETAILS_END, DETAILS_START, END, START, changelog_entries, format_notes, previous_release, render_section, splice, usable_summary,
)


def rel(tag, draft=False):
    return {"tag_name": tag, "draft": draft, "body": ""}


RELEASES = [rel("v1.17.0.1-tasker.4"), rel("v1.13.0.2-tasker.5"), rel("v1.13.0.2-tasker.2"),
            rel("v1.12.0.1-tasker.1"), rel("v1.9.1.3-tasker.1"), rel("v1.10.0-tasker.1", draft=True),
            rel("nightly")]


class PreviousReleaseTest(unittest.TestCase):
    def test_newest_build_of_the_previous_published_version(self):
        self.assertEqual("v1.13.0.2-tasker.5", previous_release(RELEASES, "v1.17.0.1-tasker.4")["tag_name"])

    def test_rebuilds_of_one_version_share_the_same_previous(self):
        self.assertEqual("v1.12.0.1-tasker.1", previous_release(RELEASES, "v1.13.0.2-tasker.2")["tag_name"])
        self.assertEqual("v1.12.0.1-tasker.1", previous_release(RELEASES, "v1.13.0.2-tasker.5")["tag_name"])

    def test_versions_compare_numerically_and_skip_drafts(self):
        self.assertEqual("v1.9.1.3-tasker.1", previous_release(RELEASES, "v1.12.0.1-tasker.1")["tag_name"])

    def test_first_release_has_none(self):
        self.assertIsNone(previous_release(RELEASES, "v1.9.1.3-tasker.1"))


class ChangelogTest(unittest.TestCase):
    ENTRIES = [{"version": v, "date": "d", "notes": ""} for v in ("1.17.0.1", "1.16.0.5", "1.13.0.2", "1.12.0.1")]

    def test_every_version_after_the_previous_release(self):
        picked = changelog_entries("1.13.0.2", "1.17.0.1", self.ENTRIES)
        self.assertEqual(["1.17.0.1", "1.16.0.5"], [e["version"] for e in picked])

    def test_first_release_gets_only_its_own_entry(self):
        self.assertEqual(["1.13.0.2"], [e["version"] for e in changelog_entries(None, "1.13.0.2", self.ENTRIES)])

    def test_format_joins_wrapped_bullets_and_marks_headings(self):
        notes = "Features:\n- New plugins API (behind a setting,\nmore to come!)\n\nFixes:\n• Fix crash"
        self.assertEqual(["*Features*", "- New plugins API (behind a setting, more to come!)", "*Fixes*", "- Fix crash"],
                         format_notes(notes))


class SectionTest(unittest.TestCase):
    META = {"version": "1.17.0.1", "previous_version": "1.13.0.2", "commits": 2, "changelog_versions": 1}
    SUMMARY = "**New**\n- Quiet time schedules\n\n**Fixed**\n- Album art on Android"

    def test_summary_first_then_collapsed_details(self):
        out = render_section(self.META, "**1.17.0.1**\n- a", "- c1\n- c2", self.SUMMARY)
        self.assertTrue(out.startswith("### What's new from upstream since 1.13.0.2\n\n**New**"))
        self.assertIn("<details><summary>Upstream's changelog</summary>", out)
        self.assertIn("<details><summary>All 2 upstream commits</summary>", out)

    def test_without_summary_the_changelog_is_shown_open(self):
        out = render_section(self.META, "**1.17.0.1**\n- a", "- c1\n- c2", None)
        self.assertIn("Upstream's changelog:\n\n**1.17.0.1**", out)
        self.assertNotIn("Upstream's changelog</summary>", out)

    def test_unusable_summaries_are_dropped(self):
        for bad in ("", "no bullets here at all, just a long sentence of prose", "- x <!-- y -->" + "z" * 50):
            self.assertFalse(usable_summary(bad), bad)
        self.assertTrue(usable_summary(self.SUMMARY))


class SpliceTest(unittest.TestCase):
    BODY = "Intro\n\n| | |\n|---|---|\n| Upstream cutoff | `abc` |\n\n### Download\n\napk\n"

    def test_changes_first_download_next_table_last(self):
        self.assertEqual(
            f"Intro\n\n{START}\nS1\n{END}\n\n### Download\n\napk\n\n"
            f"{DETAILS_START}\n### Build details\n\n| | |\n|---|---|\n| Upstream cutoff | `abc` |\n{DETAILS_END}\n",
            splice(self.BODY, "S1"),
        )

    def test_rewrites_bodies_in_the_old_layout(self):
        old = f"Intro\n\n| | |\n|---|---|\n| Upstream cutoff | `abc` |\n\n{START}\nS0\n{END}\n\n### Download\n\napk\n"
        self.assertEqual(splice(self.BODY, "S1"), splice(old, "S1"))

    def test_replaces_an_earlier_section(self):
        out = splice(splice(self.BODY, "S1"), "S2")
        self.assertEqual(1, out.count(START))
        self.assertIn("S2", out)
        self.assertNotIn("S1", out)
        self.assertEqual(splice(self.BODY, "S2"), out)

    def test_appends_when_there_is_no_download_heading(self):
        self.assertTrue(splice("Intro\n", "S1").endswith(f"{START}\nS1\n{END}\n"))

    def test_still_finds_the_cutoff_after_the_table_moves(self):
        self.assertIn("| Upstream cutoff | `abc` |", splice(splice(self.BODY, "S1"), "S2"))


if __name__ == "__main__":
    unittest.main()
