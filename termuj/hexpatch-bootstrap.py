#!/usr/bin/env python3
"""
Hexpatch an official Termux bootstrap zip so that it can be used by the
"com.termuj" app, as described in
https://github.com/termux/termux-app/issues/2734#issuecomment-1747413134

Since "com.termux" and "com.termuj" have the same length, every occurrence of
the old package name in every file (binaries included) is replaced in-place
without changing any offsets. File contents are patched by the TermujHexpatch
helper (run on a JVM), which also looks into compressed archives and fixes up
checksums. dpkg md5sums of patched files are recomputed and the files of the
overlay directory (the apt hook that hexpatches every .deb installed later) are
added to the zip unpatched, with their file modes, together with the dexed
helper used by the hook.

Usage: hexpatch-bootstrap.py <input.zip> <output.zip> <overlay-dir> <java> <helper-classes-dir> <helper.jar>
"""

import hashlib
import os
import subprocess
import sys
import tempfile
import zipfile

OLD = b"com.termux"
NEW = b"com.termuj"
assert len(OLD) == len(NEW)

OLD_PREFIX = "data/data/" + OLD.decode() + "/files/usr/"
NEW_PREFIX = "data/data/" + NEW.decode() + "/files/usr/"


def recompute_md5sums(data, files):
    """Rewrite a dpkg *.md5sums file, recomputing hashes of the (patched) files."""
    out = []
    for line in data.decode("utf-8", "surrogateescape").splitlines():
        if not line.strip():
            continue
        md5, path = line.split("  ", 1)
        for prefix in (OLD_PREFIX, NEW_PREFIX):
            if path.startswith(prefix):
                rel = path[len(prefix):]
                if rel in files:
                    md5 = hashlib.md5(files[rel]).hexdigest()
                path = NEW_PREFIX + rel
                break
        out.append(md5 + "  " + path + "\n")
    return "".join(out).encode("utf-8", "surrogateescape")


def main():
    if len(sys.argv) != 7:
        sys.exit("Usage: " + sys.argv[0] +
                 " <input.zip> <output.zip> <overlay-dir> <java> <helper-classes-dir> <helper.jar>")
    in_path, out_path, overlay_dir, java, helper_classes, helper_jar = sys.argv[1:]

    entries = []  # (ZipInfo, new name, data)
    with zipfile.ZipFile(in_path) as zin:
        for info in zin.infolist():
            name = info.filename.replace(OLD.decode(), NEW.decode())
            entries.append([info, name, zin.read(info)])

    with tempfile.TemporaryDirectory() as tmp:
        paths = {}
        for i, (info, _, data) in enumerate(entries):
            if not info.is_dir():
                paths[i] = os.path.join(tmp, str(i))
                with open(paths[i], "wb") as f:
                    f.write(data)
        result = subprocess.run([java, "-cp", helper_classes, "TermujHexpatch"],
                                input=b"\0".join(p.encode() for p in paths.values()),
                                stdout=subprocess.PIPE, check=True)
        patched_count = int(result.stdout)
        for i, path in paths.items():
            with open(path, "rb") as f:
                entries[i][2] = f.read()

    files = {name: data for info, name, data in entries if not info.is_dir()}

    overlay = {}
    for root, _, names in os.walk(overlay_dir):
        for n in names:
            full = os.path.join(root, n)
            rel = os.path.relpath(full, overlay_dir).replace(os.sep, "/")
            with open(full, "rb") as f:
                overlay[rel] = (f.read(), os.stat(full).st_mode)
    with open(helper_jar, "rb") as f:
        overlay["libexec/termuj-hexpatch.jar"] = (f.read(), 0o100400)

    tmp_path = out_path + ".tmp"
    with zipfile.ZipFile(tmp_path, "w", zipfile.ZIP_DEFLATED) as zout:
        for info, name, data in entries:
            if name in overlay:
                continue
            if name.startswith("var/lib/dpkg/info/") and name.endswith(".md5sums"):
                data = recompute_md5sums(data, files)
            new_info = zipfile.ZipInfo(name, date_time=info.date_time)
            new_info.external_attr = info.external_attr
            new_info.compress_type = zipfile.ZIP_STORED if info.is_dir() else zipfile.ZIP_DEFLATED
            zout.writestr(new_info, data)
        for rel in sorted(overlay):
            new_info = zipfile.ZipInfo(rel, date_time=(2020, 1, 1, 0, 0, 0))
            data, mode = overlay[rel]
            new_info.external_attr = mode << 16
            new_info.compress_type = zipfile.ZIP_DEFLATED
            zout.writestr(new_info, data)
    os.replace(tmp_path, out_path)

    print("Hexpatched %d of %d entries, added %d overlay files: %s" %
          (patched_count, len(entries), len(overlay), out_path))


if __name__ == "__main__":
    main()
