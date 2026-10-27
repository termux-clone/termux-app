#!/usr/bin/env python3
"""
Build the termuj-hexpatch .deb package, which contains the apt hook that
hexpatches every .deb built for the official Termux app, the dexed
TermujHexpatch helper used by the hook and the apt configuration.

The package is preinstalled in the bootstrap by hexpatch-bootstrap.py. As the
hook would patch the package itself when it is upgraded with apt, it is
verified that no file (including the content of the helper jar) contains the
old package name.

Usage: build-hexpatch-deb.py <package-dir> <helper.jar> <output-dir>
<package-dir> contains the "control" file and the "files" directory, whose
content is installed relative to $PREFIX. The "Installed-Size" field is added.
Prints the path of the built package.
"""

import hashlib
import io
import os
import sys
import tarfile
import zipfile

OLD = b"com.termu" + b"x"
PREFIX = "data/data/com.termuj/files/usr/"
MTIME = 1577836800  # 2020-01-01, for reproducible builds
CONFFILES_DIR = "etc/"


def contains_old(data):
    if OLD in data:
        return True
    if data[:4] == b"PK\x03\x04":
        with zipfile.ZipFile(io.BytesIO(data)) as z:
            return any(OLD in z.read(n) or OLD in n.encode() for n in z.namelist())
    return False


def tar_bytes(members):
    """members: list of (name, data or None for a directory, mode)"""
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w:xz", format=tarfile.GNU_FORMAT) as tar:
        for name, data, mode in members:
            info = tarfile.TarInfo(name)
            info.mtime = MTIME
            info.uid = info.gid = 0
            info.uname = info.gname = "root"
            info.mode = mode
            if data is None:
                info.type = tarfile.DIRTYPE
                tar.addfile(info)
            else:
                info.size = len(data)
                tar.addfile(info, io.BytesIO(data))
    return buf.getvalue()


def ar_bytes(members):
    out = b"!<arch>\n"
    for name, data in members:
        out += ("%-16s%-12d%-6d%-6d%-8s%-10d`\n" % (name, MTIME, 0, 0, "100644", len(data))).encode()
        out += data
        if len(data) % 2:
            out += b"\n"
    return out


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

    bad = [rel for rel, (data, _) in files.items() if contains_old(data) or OLD in rel.encode()]
    if bad:
        sys.exit("Files of the package contain the old package name: " + ", ".join(sorted(bad)))

    with open(os.path.join(pkg_dir, "control"), "rb") as f:
        control = f.read().decode()
    fields = dict(line.split(": ", 1) for line in control.splitlines() if line and not line[0].isspace())
    installed_size = sum((len(d) + 1023) // 1024 for d, _ in files.values())
    control = control.replace("Version: ", "Installed-Size: %d\nVersion: " % installed_size, 1)

    dirs = set()
    for rel in files:
        parts = (PREFIX + rel).split("/")[:-1]
        for i in range(1, len(parts) + 1):
            dirs.add("/".join(parts[:i]))
    data_members = [("./", None, 0o755)]
    for path in sorted(dirs | {PREFIX + rel for rel in files}):
        if path in dirs:
            data_members.append(("./" + path + "/", None, 0o755))
        else:
            data, mode = files[path[len(PREFIX):]]
            data_members.append(("./" + path, data, mode))

    md5sums = "".join("%s  %s\n" % (hashlib.md5(files[rel][0]).hexdigest(), PREFIX + rel) for rel in sorted(files))
    conffiles = "".join("/%s%s\n" % (PREFIX, rel) for rel in sorted(files) if rel.startswith(CONFFILES_DIR))
    control_members = [("./", None, 0o755), ("./control", control.encode(), 0o644),
                       ("./md5sums", md5sums.encode(), 0o644)]
    if conffiles:
        control_members.append(("./conffiles", conffiles.encode(), 0o644))

    os.makedirs(out_dir, exist_ok=True)
    out_path = os.path.join(out_dir, "%s_%s_%s.deb" % (fields["Package"], fields["Version"], fields["Architecture"]))
    with open(out_path, "wb") as f:
        f.write(ar_bytes([("debian-binary", b"2.0\n"), ("control.tar.xz", tar_bytes(control_members)),
                          ("data.tar.xz", tar_bytes(data_members))]))
    print(out_path)


if __name__ == "__main__":
    main()
