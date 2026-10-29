#!/usr/bin/env python3
"""
Build the termuj-hexpatch .deb package, which contains the apt hook that
hexpatches every .deb built for the official Termux app, the dexed
TermujHexpatch helper used by the hook and the apt configuration.

The package is preinstalled in the bootstrap by hexpatch-bootstrap.py. As the
hook would patch the package itself when it is upgraded with apt, debpkg.py
verifies that no file (including the content of the helper jar) contains the
old package name.

Usage: build-hexpatch-deb.py <package-dir> <helper.jar> <output-dir>
<package-dir> contains the "control" file and the "files" directory, whose
content is installed relative to $PREFIX. The "Installed-Size" field is added.
Prints the path of the built package.
"""

import os
import sys

from debpkg import build_deb


def main():
    if len(sys.argv) != 4:
        sys.exit("Usage: " + sys.argv[0] + " <package-dir> <helper.jar> <output-dir>")
    pkg_dir, helper_jar, out_dir = sys.argv[1:]

    files = {}  # path relative to $PREFIX -> (data, mode)
    files_dir = os.path.join(pkg_dir, "files")
    for root, _, names in os.walk(files_dir):
        for n in names:
            full = os.path.join(root, n)
            rel = os.path.relpath(full, files_dir).replace(os.sep, "/")
            with open(full, "rb") as f:
                files[rel] = (f.read(), 0o755 if os.stat(full).st_mode & 0o111 else 0o644)
    with open(helper_jar, "rb") as f:
        # Not read-only, as dpkg reopens unpacked files for writing to sync them. The hook
        # makes it read-only before running it, as Android 14 refuses writable dex files.
        files["libexec/termuj-hexpatch.jar"] = (f.read(), 0o644)

    with open(os.path.join(pkg_dir, "control"), "rb") as f:
        control = f.read().decode()
    print(build_deb(control, files, out_dir))


if __name__ == "__main__":
    main()
