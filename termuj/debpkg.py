"""
Build reproducible .deb packages for the Termuj app, whose files are installed
relative to its $PREFIX. Files under etc/ are marked as conffiles.

As the apt hook of termuj-hexpatch would patch the packages, it is verified
that no file (including the content of zip files) contains the old package name.
"""

import hashlib
import io
import os
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


def build_deb(control, files, out_dir, scripts=None):
    """
    control: content of the control file, without the "Installed-Size" field.
    files: {path relative to $PREFIX: (data, mode)}.
    scripts: {maintainer script name, e.g. "postinst": content}.
    Returns the path of the built package.
    """
    scripts = scripts or {}
    bad = [rel for rel, (data, _) in files.items() if contains_old(data) or OLD in rel.encode()]
    bad += [name for name, data in scripts.items() if OLD in data.encode()]
    if OLD in control.encode():
        bad.append("control")
    if bad:
        raise SystemExit("Files of the package contain the old package name: " + ", ".join(sorted(bad)))

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
    for name in sorted(scripts):
        control_members.append(("./" + name, scripts[name].encode(), 0o755))

    os.makedirs(out_dir, exist_ok=True)
    out_path = os.path.join(out_dir, "%s_%s_%s.deb" % (fields["Package"], fields["Version"], fields["Architecture"]))
    with open(out_path, "wb") as f:
        f.write(ar_bytes([("debian-binary", b"2.0\n"), ("control.tar.xz", tar_bytes(control_members)),
                          ("data.tar.xz", tar_bytes(data_members))]))
    return out_path
