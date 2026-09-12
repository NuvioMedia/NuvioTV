"""Summarize Android lint without including source snippets or diagnostic messages.

Usage: python scripts/lint_inventory.py app/build/reports/lint-results-fullDebug.xml
The output is an inventory, never a lint baseline or suppression file.
"""

import argparse
import collections
import json
from pathlib import Path
import xml.etree.ElementTree as ET


def inventory(report: Path) -> dict:
    issues = ET.parse(report).getroot().findall("issue")
    by_id = collections.Counter(issue.get("id", "unknown") for issue in issues)
    by_severity = collections.Counter(issue.get("severity", "unknown") for issue in issues)
    return {
        "report": report.name,
        "total": len(issues),
        "bySeverity": dict(sorted(by_severity.items())),
        "byIssue": dict(sorted(by_id.items(), key=lambda item: (-item[1], item[0]))),
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path)
    args = parser.parse_args()
    print(json.dumps(inventory(args.report), indent=2))
