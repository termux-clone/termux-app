#!/usr/bin/env python3
"""
Hexpatch an official Termux bootstrap zip so that it can be used by the
"com.termuj" app, as described in
https://github.com/termux/termux-app/issues/2734#issuecomment-1747413134

Since "com.termux" and "com.termuj" have the same length, every occurrence of
the old package name in every file (binaries included) is replaced in-place
without changing any offsets. File contents are patched by the TermujHexpatch
helper (run on a JVM), which also looks into compressed archives and fixes up
checksums. dpkg md5sums of patched files are recomputed.

The files of the overlay directory replace bootstrap files, and the
termuj-hexpatch package (the apt hook that hexpatches every .deb installed
later) is preinstalled: its files are added and it is registered in the dpkg
database like the other bootstrap packages. Both are added unpatched.

Usage: hexpatch-bootstrap.py <input.zip> <output.zip> <overlay-dir> <java> <helper-classes-dir> <termuj-hexpatch.deb>
"""

import hashlib
import io
import os
import stat
import subprocess
import sys
import tarfile
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


def read_deb(path):
    """Returns the members of the control and data tarballs of a .deb as {name: (TarInfo, data)}."""
    with open(path, "rb") as f:
        deb = f.read()
    if deb[:8] != b"!<arch>\n":
        sys.exit("Not a deb: " + path)
    tars = {}
    pos = 8
    while pos + 60 <= len(deb):
        name = deb[pos:pos + 16].decode().strip().rstrip("/")
        size = int(deb[pos + 48:pos + 58])
        data = deb[pos + 60:pos + 60 + size]
        pos += 60 + size + size % 2
        for kind in ("control", "data"):
            if name.startswith(kind + ".tar"):
                members = {}
                with tarfile.open(fileobj=io.BytesIO(data)) as tar:
                    for m in tar.getmembers():
                        members[m.name] = (m, tar.extractfile(m).read() if m.isfile() else None)
                tars[kind] = members
    return tars["control"], tars["data"]


def preinstall_deb(path):
    """Returns the zip entries {name: (data, mode)} that preinstall a .deb like the bootstrap packages,
    and its dpkg status entry."""
    control, data = read_deb(path)
    entries = {}
    listing = []
    for name, (m, content) in data.items():
        path_name = "/" + name[2:] if name.startswith("./") else "/" + name
        if path_name in ("/", "/."):
            listing.append("/.")
            continue
        if m.isdir():
            listing.append(path_name.rstrip("/") + "/")
        else:
            listing.append(path_name)
        if not path_name.startswith("/" + NEW_PREFIX):
            continue
        rel = path_name[len(NEW_PREFIX) + 1:]
        if m.isdir():
            entries[rel.rstrip("/") + "/"] = (None, stat.S_IFDIR | m.mode)
        elif m.isfile():
            entries[rel] = (content, stat.S_IFREG | m.mode)
        else:
            sys.exit("Unsupported member in " + path + ": " + name)

    ctrl = control["./control"][1].decode()
    package = next(line.split(": ", 1)[1] for line in ctrl.splitlines() if line.startswith("Package: "))
    info = "var/lib/dpkg/info/" + package
    entries[info + ".list"] = ("\n".join(listing).encode() + b"\n", stat.S_IFREG | 0o644)
    for name in ("md5sums", "conffiles"):
        if "./" + name in control:
            entries[info + "." + name] = (control["./" + name][1], stat.S_IFREG | 0o644)
    status = ctrl.rstrip("\n") + "\nStatus: install ok installed\n"
    return entries, package, status


def main():
    if len(sys.argv) != 7:
        sys.exit("Usage: " + sys.argv[0] +
                 " <input.zip> <output.zip> <overlay-dir> <java> <helper-classes-dir> <termuj-hexpatch.deb>")
    in_path, out_path, overlay_dir, java, helper_classes, deb_path = sys.argv[1:]

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

    names = {name for _, name, _ in entries}
    deb_entries, package, status = preinstall_deb(deb_path)
    if "var/lib/dpkg/info/" + package + ".list" in names:
        sys.exit("Package " + package + " is already part of the bootstrap")
    added = dict(overlay)
    for name, value in deb_entries.items():
        if name.endswith("/"):
            if name not in names:
                added[name] = value
        elif name in names or name in overlay:
            sys.exit("File " + name + " of " + package + " is already part of the bootstrap")
        else:
            added[name] = value

    tmp_path = out_path + ".tmp"
    with zipfile.ZipFile(tmp_path, "w", zipfile.ZIP_DEFLATED) as zout:
        for info, name, data in entries:
            if name in overlay:
                continue
            if name.startswith("var/lib/dpkg/info/") and name.endswith(".md5sums"):
                data = recompute_md5sums(data, files)
            if name == "var/lib/dpkg/status":
                data = data.rstrip(b"\n") + b"\n\n" + status.encode()
            new_info = zipfile.ZipInfo(name, date_time=info.date_time)
            new_info.external_attr = info.external_attr
            new_info.compress_type = zipfile.ZIP_STORED if info.is_dir() else zipfile.ZIP_DEFLATED
            zout.writestr(new_info, data)
        for name in sorted(added):
            data, mode = added[name]
            new_info = zipfile.ZipInfo(name, date_time=(2020, 1, 1, 0, 0, 0))
            new_info.external_attr = mode << 16
            if data is None:
                new_info.compress_type = zipfile.ZIP_STORED
                data = b""
            else:
                new_info.compress_type = zipfile.ZIP_DEFLATED
            zout.writestr(new_info, data)
    os.replace(tmp_path, out_path)

    print("Hexpatched %d of %d entries, added %d overlay files, preinstalled %s: %s" %
          (patched_count, len(entries), len(overlay), os.path.basename(deb_path), out_path))

if __name__ == "__main__":
    main()
