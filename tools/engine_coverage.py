# SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Line coverage of the Rust engine's ported packages against that of their Java twins.

The port holds every package of njmule-engine/tests/ported_areas.txt to at least the line coverage of
the Java package it replaces. The Java figures, taken from JaCoCo, are kept in
njmule-engine/tests/java_coverage.txt: the Java engine changes only by fixes until it is removed. The
Rust figures come from cargo-llvm-cov. The Java package org/njmule/core/<p> is twinned with the Rust
files directly in njmule-engine/src/<p>.

Usage, from the repository's root:
  python tools/engine_coverage.py baseline njmule-core/build/reports/jacoco/test/jacocoTestReport.xml
      rewrites java_coverage.txt, after ./gradlew :njmule-core:jacocoTestReport
  python tools/engine_coverage.py check <coverage.json>
      with the output of `cargo llvm-cov --json --summary-only --output-path <coverage.json>`;
      fails when a ported package is below its Java twin
"""

import json
import sys
import xml.etree.ElementTree as ElementTree  # Only our own build's JaCoCo report is read.
from pathlib import Path

TESTS = Path(__file__).resolve().parent.parent / "njmule-engine" / "tests"
AREAS = TESTS / "ported_areas.txt"
BASELINE = TESTS / "java_coverage.txt"
JAVA_ROOT = "org/njmule/core/"
RUST_ROOT = "/njmule-engine/src/"
HEADER = """\
# SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Line coverage of the Java engine's packages, from JaCoCo: package, lines covered, lines. The Rust twins
# of the packages in ported_areas.txt must not fall below it (tools/engine_coverage.py, which rewrites it).

"""


def listed(path):
    lines = (line.strip() for line in path.read_text(encoding="utf-8").splitlines())
    return [line for line in lines if line and not line.startswith("#")]


def percent(covered, total):
    return 100 * covered / total if total else 100.0


def baseline(report):
    rows = []
    for package in ElementTree.parse(report).getroot().iter("package"):
        name = package.get("name", "")
        lines = [counter for counter in package.findall("counter") if counter.get("type") == "LINE"]
        if name.startswith(JAVA_ROOT) and lines:
            covered = int(lines[0].get("covered"))
            total = covered + int(lines[0].get("missed"))
            rows.append(f"{name[len(JAVA_ROOT):]}/ {covered} {total}")
    BASELINE.write_text(HEADER + "\n".join(sorted(rows)) + "\n", encoding="utf-8", newline="\n")
    print(f"{len(rows)} packages written to {BASELINE}")
    return 0


def rust_packages(report):
    packages = {}
    for file in json.loads(Path(report).read_text(encoding="utf-8"))["data"][0]["files"]:
        path = file["filename"].replace("\\", "/")
        if RUST_ROOT not in path:
            continue
        folder, _, _ = path.split(RUST_ROOT, 1)[1].rpartition("/")
        package = folder + "/" if folder else ""
        covered, total = packages.get(package, (0, 0))
        lines = file["summary"]["lines"]
        packages[package] = (covered + lines["covered"], total + lines["count"])
    return packages


def check(report):
    areas = listed(AREAS)
    java = {package: (int(covered), int(total)) for package, covered, total in map(str.split, listed(BASELINE))}
    rust = rust_packages(report)
    failures = []
    sums = {"java": [0, 0], "rust": [0, 0]}
    print(f"{'package':28} {'java':>14} {'rust':>14}")
    for package in sorted(p for p in java if any(p.startswith(area) for area in areas)):
        (java_covered, java_total), (rust_covered, rust_total) = java[package], rust.get(package, (0, 0))
        if java_total == 0:
            continue
        if rust_total == 0:
            failures.append(f"{package}: no Rust code")
        elif rust_covered * java_total < java_covered * rust_total:
            failures.append(f"{package}: {percent(rust_covered, rust_total):.1f}% in Rust, "
                            f"{percent(java_covered, java_total):.1f}% in Java")
        for side, covered, total in (("java", java_covered, java_total), ("rust", rust_covered, rust_total)):
            sums[side][0] += covered
            sums[side][1] += total
        print(f"{package:28} {percent(java_covered, java_total):6.1f}% {java_total:6} "
              f"{percent(rust_covered, rust_total):6.1f}% {rust_total:6}")
    print(f"{'ported areas':28} {percent(*sums['java']):6.1f}% {sums['java'][1]:6} "
          f"{percent(*sums['rust']):6.1f}% {sums['rust'][1]:6}")
    if failures:
        print("\nBelow the line coverage of the Java engine:\n  " + "\n  ".join(failures))
        return 1
    return 0


def main(arguments):
    commands = {"baseline": baseline, "check": check}
    if len(arguments) != 2 or arguments[0] not in commands:
        print(__doc__)
        return 2
    return commands[arguments[0]](arguments[1])


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
