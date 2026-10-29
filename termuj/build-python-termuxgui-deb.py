#!/usr/bin/env python3
"""
Build the python-termuxgui .deb package: the termuxgui module (Python bindings
for the Termux:GUI plugin) installed like the Python packages of the Termux
repository, with the package names of the Termux app and of the Termux:GUI
plugin changed to those of the Termuj app and of the Termuj:GUI plugin.

The files are installed in lib/python<version>/site-packages, so the package
depends on that Python version of the Termux repository.

Usage: build-python-termuxgui-deb.py <source-dir> <python-version> <output-dir>
<source-dir> is the source of github.com/tareksander/termux-gui-python-bindings.
<python-version> is the "<major>.<minor>" version of the python package.
Prints the path of the built package.
"""

import os
import re
import shutil
import subprocess
import sys
import tempfile

from debpkg import PREFIX, build_deb

OLD = b"com.termu" + b"x"
NEW = b"com.termuj"
PACKAGE = "python-termuxgui"
ABS_PREFIX = "/" + PREFIX.rstrip("/")


def main():
    if len(sys.argv) != 4:
        sys.exit("Usage: " + sys.argv[0] + " <source-dir> <python-version> <output-dir>")
    source_dir, python_version, out_dir = sys.argv[1:]
    major, minor = (int(v) for v in re.fullmatch(r"(\d+)\.(\d+)", python_version).groups())
    site_packages = "lib/python%d.%d/site-packages/" % (major, minor)

    with tempfile.TemporaryDirectory() as tmp:
        src = os.path.join(tmp, "src")
        shutil.copytree(source_dir, src, ignore=shutil.ignore_patterns(".git"))
        patched = []
        for root, _, names in os.walk(os.path.join(src, "src")):
            for n in names:
                path = os.path.join(root, n)
                with open(path, "rb") as f:
                    data = f.read()
                if OLD in data:
                    with open(path, "wb") as f:
                        f.write(data.replace(OLD, NEW))
                    patched.append(os.path.relpath(path, src))
        if not patched:
            sys.exit("No file of the module contains the old package name")
        print("Patched: " + ", ".join(sorted(patched)), file=sys.stderr)

        wheel_dir = os.path.join(tmp, "wheel")
        install_dir = os.path.join(tmp, "install")
        pip = [sys.executable, "-m", "pip", "--disable-pip-version-check", "-q"]
        subprocess.run(pip + ["wheel", "--no-deps", "-w", wheel_dir, src], check=True, stdout=sys.stderr)
        wheels = os.listdir(wheel_dir)
        if len(wheels) != 1 or not wheels[0].endswith("-py3-none-any.whl"):
            sys.exit("Expected one pure Python wheel, found: " + ", ".join(wheels))
        subprocess.run(pip + ["install", "--no-deps", "--no-compile", "--target", install_dir,
                              os.path.join(wheel_dir, wheels[0])], check=True, stdout=sys.stderr)

        # Records the path of the temporary wheel, as if it was installed from that URL
        for dist_info in os.listdir(install_dir):
            direct_url = os.path.join(install_dir, dist_info, "direct_url.json")
            if dist_info.endswith(".dist-info") and os.path.exists(direct_url):
                os.remove(direct_url)
                record = os.path.join(install_dir, dist_info, "RECORD")
                with open(record) as f:
                    lines = [l for l in f if not l.startswith(dist_info + "/direct_url.json,")]
                with open(record, "w") as f:
                    f.writelines(lines)

        files = {}  # path relative to $PREFIX -> (data, mode)
        version = None
        for root, _, names in os.walk(install_dir):
            for n in names:
                full = os.path.join(root, n)
                rel = os.path.relpath(full, install_dir).replace(os.sep, "/")
                if rel.split("/")[0] == "bin":
                    sys.exit("Unexpected script installed by the wheel: " + rel)
                with open(full, "rb") as f:
                    files[site_packages + rel] = (f.read(), 0o644)
                m = re.fullmatch(r"termuxgui-([^/]+)\.dist-info/METADATA", rel)
                if m:
                    version = m.group(1)
        if not version:
            sys.exit("No termuxgui dist-info found")
        with open(os.path.join(source_dir, "LICENSE"), "rb") as f:
            files["share/doc/%s/copyright" % PACKAGE] = (f.read(), 0o644)

    control = """Package: %s
Version: %s
Architecture: all
Maintainer: @w311ang
Homepage: https://github.com/tareksander/termux-gui-python-bindings
Depends: python (>= %d.%d), python (<< %d.%d)
Description: Python bindings for the Termux:GUI plugin, for the Termuj app
 The termuxgui Python module, using the Termuj app and the Termuj:GUI plugin
 instead of the Termux app and the Termux:GUI plugin.
""" % (PACKAGE, version, major, minor, major, minor + 1)

    # Like the maintainer scripts of the Python packages of the Termux repository
    postinst = """#!%(p)s/bin/sh
if [ "$1" != "configure" ]; then
exit 0
fi
if [ -f "%(p)s/bin/py3compile" ]; then
LD_PRELOAD='' "%(p)s/bin/py3compile" -p "%(pkg)s" "%(p)s/lib/python%(v)s/"
fi
exit 0
""" % {"p": ABS_PREFIX, "pkg": PACKAGE, "v": python_version}
    prerm = """#!%(p)s/bin/sh
if [ "$1" != "remove" ]; then
exit 0
fi
if [ -f "%(p)s/bin/py3clean" ]; then
LD_PRELOAD='' "%(p)s/bin/py3clean" -p "%(pkg)s"
fi
exit 0
""" % {"p": ABS_PREFIX, "pkg": PACKAGE}

    print(build_deb(control, files, out_dir, {"postinst": postinst, "prerm": prerm}))


if __name__ == "__main__":
    main()
