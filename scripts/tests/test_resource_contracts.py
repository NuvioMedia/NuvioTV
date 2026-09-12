"""Regression checks for translated Android formatting and plural contracts."""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

RES = Path(__file__).resolve().parents[2] / "app/src/main/res"


class ResourceContractsTest(unittest.TestCase):
    def test_formatted_error_and_expiry_translations_keep_argument_positions_and_types(self):
        base = {e.get("name"): e for e in ET.parse(RES / "values/strings.xml").getroot()}
        names = {
            "error_meta_tried_none", "error_meta_tried_generic", "error_stream_tried_none",
            "error_stream_tried_generic", "error_meta_tried_issues", "error_stream_tried_issues",
            "auth_qr_expires", "torrent_error_binary_missing",
            "torrent_error_process_died", "torrent_error_start_timeout",
        }
        def arguments(element):
            return set(re.findall(r"%(\d+\$)?([sd])", "".join(element.itertext())))
        for path in RES.glob("values*/strings.xml"):
            for element in ET.parse(path).getroot():
                if element.get("name") in names:
                    with self.subTest(locale=path.parent.name, name=element.get("name")):
                        self.assertEqual(arguments(base[element.get("name")]), arguments(element))

    def test_literal_percent_labels_are_not_format_strings(self):
        names = {"supporters_contributors_donation_progress_remaining", "playback_cache_info_auto"}
        for path in RES.glob("values*/strings.xml"):
            for element in ET.parse(path).getroot():
                if element.get("name") in names:
                    with self.subTest(locale=path.parent.name, name=element.get("name")):
                        self.assertEqual("false", element.get("formatted"))
                        self.assertNotIn("%%", element.text or "")

    def test_translated_plurals_have_required_quantity_branches(self):
        required = {
            "values-ar": {"zero", "one", "two", "few", "many", "other"},
            "values-iw": {"one", "two", "other"},
            **{locale: {"one", "many", "other"} for locale in
               ["values-it", "values-fr", "values-pt-rBR", "values-pt-rPT", "values-b+es+419"]},
        }
        for locale, quantities in required.items():
            for plural in ET.parse(RES / locale / "strings.xml").getroot().findall("plurals"):
                with self.subTest(locale=locale, name=plural.get("name")):
                    self.assertTrue(quantities.issubset({item.get("quantity") for item in plural}))


if __name__ == "__main__":
    unittest.main()
