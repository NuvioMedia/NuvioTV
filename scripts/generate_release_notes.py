#!/usr/bin/env python3

from __future__ import annotations

import argparse
import os
import sys

from release_notes import generate_release_notes, render_notes, render_quality_report


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--from", dest="start", required=True)
    parser.add_argument("--to", dest="end", required=True)
    parser.add_argument(
        "--repository",
        default=os.environ.get("GITHUB_REPOSITORY", ""),
    )
    parser.add_argument("--exclude", default="")
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--quality-report")
    parser.add_argument("--strict", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    exclusions = tuple(
        item.strip() for item in args.exclude.split(",") if item.strip()
    )
    try:
        result = generate_release_notes(
            start=args.start,
            end=args.end,
            repository=args.repository,
            exclusions=exclusions,
            offline=args.offline,
        )
    except (RuntimeError, ValueError) as error:
        print(f"Release-note generation failed: {error}", file=sys.stderr)
        return 1
    notes = render_notes(result)
    if notes:
        sys.stdout.write(notes)
    if args.quality_report:
        try:
            with open(args.quality_report, "w", encoding="utf-8") as report:
                report.write(render_quality_report(result))
        except OSError as error:
            print(f"Could not write quality report: {error}", file=sys.stderr)
            return 1
    if args.strict and result.blocking_issues:
        print(
            "Release-note quality checks failed. Review the quality report.",
            file=sys.stderr,
        )
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
