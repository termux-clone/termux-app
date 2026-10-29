#!/usr/bin/env python3
"""
Rename an official Termux source tree (the termux-app libraries or a plugin
app) in place from "com.termux" to "com.termuj" and from "Termux" to "Termuj"
in app names, so that it can be built for and used with the Termuj app.

URLs and the external libraries listed in KEEP are left unchanged so that
links to the official Termux sites keep working and the libraries resolve.
Directories named com/termux are moved to com/termuj.

Usage: rename-to-termuj.py <git-source-dir>
"""

import os
import re
import subprocess
import sys

URL = r"https?://[^\s\"'<>()\[\]]*"
# External libraries that keep their com.termux maven coordinates and Java packages
KEEP = [
    r"com\.termux:termux-am-library\b",
    r"com\.termux\.am\b",
]
REPLACEMENTS = [
    (r"com\.termux", "com.termuj"),
    (r"com_termux", "com_termuj"),
    (r"com/termux", "com/termuj"),
    (r"Termux:(API|Boot|Float|GUI|Styling|Tasker|Widget)\b", r"Termuj:\1"),
    (r'"Termux"', '"Termuj"'),
    (r">Termux<", ">Termuj<"),
    (r"termux-(api|boot|float|styling|tasker|widget)-app_", r"termuj-\1-app_"),
]
PATTERN = re.compile("(" + "|".join([URL] + KEEP) + ")|" + "|".join("(" + p + ")" for p, _ in REPLACEMENTS))
SUBS = [(re.compile(p), r) for p, r in REPLACEMENTS]


def replace(match):
    if match.group(1):
        return match.group(1)
    text = match.group(0)
    for regex, repl in SUBS:
        if regex.fullmatch(text):
            return regex.sub(repl, text)
    return text


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    os.chdir(sys.argv[1])

    files = subprocess.run(["git", "ls-files", "-z"], check=True, capture_output=True).stdout.split(b"\0")
    for name in filter(None, files):
        path = os.fsdecode(name)
        if os.path.islink(path) or not os.path.isfile(path):
            continue
        with open(path, "rb") as f:
            data = f.read()
        if b"\0" in data:
            continue
        try:
            text = data.decode("utf-8")
        except UnicodeDecodeError:
            continue
        new_text = PATTERN.sub(replace, text)
        if new_text != text:
            with open(path, "w", encoding="utf-8", newline="") as f:
                f.write(new_text)

    for root, _, _ in os.walk(".", topdown=False):
        if ".git" in root.split(os.sep):
            continue
        if os.path.basename(root) == "termux" and os.path.basename(os.path.dirname(root)) == "com":
            os.rename(root, os.path.join(os.path.dirname(root), "termuj"))


if __name__ == "__main__":
    main()
