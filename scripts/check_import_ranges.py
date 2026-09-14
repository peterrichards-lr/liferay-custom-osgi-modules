#!/usr/bin/env python3
"""Verify -- or regenerate -- every declared Import-Package range against the
target platform, without changing the buildpath.

Why this exists (issue #33)
---------------------------
The modules compile against the aggregate `release.dxp.api` jar, which carries
no `Export-Package` header. bnd derives an import's version from the exporter's
manifest, so with nothing to read it emits an import with NO version at all --
which resolves against anything and then binds to whatever the portal happens to
have. The ranges in each `bnd.bnd` are therefore hand-written and load-bearing.

Issue #33 proposed fixing that by putting real OSGi bundles on the buildpath so
bnd could derive them. This takes the cheaper route: the aggregate jar does
contain a `packageinfo` file per package, carrying the exported version for the
line. bnd will not consult those for classpath imports -- that part of #33 is
correct -- but nothing stops us reading them ourselves.

What it does NOT replace
------------------------
`resolve` validates the whole wiring against the real distro, and the
per-package manifest guard in publish.yml catches an import that reached the
manifest unversioned. This is a third, cheaper check that catches a range
pinned to the WRONG LINE, and reports every one of them rather than stopping at
the first like the OSGi resolver does.
"""

import argparse
import pathlib
import re
import sys
import zipfile

RANGE_RE = re.compile(r'version="([^"]+)"')
VERSION_RE = re.compile(r"version\s+(\S+)")


def read_packageinfo(zf, package):
    """Exported version of `package` on this line, or None if absent."""
    try:
        raw = zf.read(package.replace(".", "/") + "/packageinfo").decode()
    except KeyError:
        return None
    match = VERSION_RE.search(raw)
    return match.group(1) if match else None


def expected_range(version, policy):
    """The range this repository declares for an exported `version`.

    `major` floors to the major, matching what is written by hand today.
    `bnd` matches bnd's own consumer policy ${range;[==,+)}, which floors to the
    compiled minor and is therefore narrower -- see #33 for why the two differ.
    """
    parts = version.split(".")
    major = int(parts[0])
    minor = int(parts[1]) if len(parts) > 1 else 0

    floor = f"{major}.0" if policy == "major" else f"{major}.{minor}"

    return f"[{floor},{major + 1}.0)"


def import_clauses(text):
    """Import-Package clauses from a bnd.bnd, with continuations joined."""
    joined = text.replace("\\\n", "")

    for line in joined.splitlines():
        if not line.startswith("Import-Package:"):
            continue

        body = line.split(":", 1)[1]

        # Split on commas outside quotes so a uses:="a,b" directive does not
        # fragment a clause.
        clauses, buf, quoted = [], "", False

        for char in body:
            if char == '"':
                quoted = not quoted
            if char == "," and not quoted:
                clauses.append(buf)
                buf = ""
            else:
                buf += char

        clauses.append(buf)

        return [c.strip() for c in clauses if c.strip()]

    return []


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jar", help="path to the release.dxp.api jar")
    parser.add_argument(
        "--modules-dir", default="modules", help="directory holding the modules")
    parser.add_argument(
        "--policy", choices=("major", "bnd"), default="major",
        help="major (default, floors to the major) or bnd (${range;[==,+)})")
    parser.add_argument(
        "--fix", action="store_true",
        help="rewrite each bnd.bnd in place instead of only reporting")
    args = parser.parse_args()

    zf = zipfile.ZipFile(args.jar)

    problems = 0
    checked = 0

    for bnd in sorted(pathlib.Path(args.modules_dir).glob("*/bnd.bnd")):
        text = bnd.read_text()
        module = bnd.parent.name
        rewritten = text

        for clause in import_clauses(text):
            if not clause.startswith("com.liferay"):
                continue

            package = clause.split(";")[0].strip()
            match = RANGE_RE.search(clause)
            declared = match.group(1) if match else None

            actual = read_packageinfo(zf, package)
            checked += 1

            if actual is None:
                # Not exported by this line at all. Either the package moved or
                # the target platform changed under the module; both are the
                # kind of thing a silent build should not hide.
                print(
                    f"::error::{module}: {package} has no packageinfo in the "
                    f"target platform -- it may not exist on this line")
                problems += 1
                continue

            expected = expected_range(actual, args.policy)

            if declared == expected:
                continue

            problems += 1
            print(
                f"::error::{module}: {package} declares "
                f"{declared or '<no range>'} but this line exports {actual} "
                f"(expected {expected})")

            if args.fix:
                if declared:
                    rewritten = rewritten.replace(
                        f'{package};version="{declared}"',
                        f'{package};version="{expected}"')
                else:
                    rewritten = rewritten.replace(
                        f"\t{package},", f'\t{package};version="{expected}",')

        if args.fix and (rewritten != text):
            bnd.write_text(rewritten)
            print(f"fixed {bnd}")

    print(
        f"\n{checked} com.liferay imports checked against "
        f"{pathlib.Path(args.jar).name}, {problems} problem(s)")

    return 1 if (problems and not args.fix) else 0


if __name__ == "__main__":
    sys.exit(main())
