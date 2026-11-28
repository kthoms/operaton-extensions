#!/usr/bin/env python3
# Copyright the Operaton contributors.
# SPDX-License-Identifier: Apache-2.0
"""Regenerates the extension tables of docs/catalog.md from the extension.yml manifests.

Usage: tools/generate-catalog.py [--check]

The Plugins, Connectors and Clients tables sit between marker comments
(<!-- catalog:plugins:begin --> ... <!-- catalog:plugins:end -->, same for connectors and clients) and are
rewritten from the manifests of plugins/*, connectors/* and clients/*. With --check nothing is written; the
exit status is 1 when the file is out of date (CI and the release check use it). Standard library only.
"""
import glob
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
CATALOG = os.path.join(ROOT, "docs", "catalog.md")

CLIENT_PACKAGE = {
    "js": "`@kthoms/operaton-external-task-client` (npm)",
    "python": "`operaton-external-task-client` (PyPI)",
    "go": "`github.com/kthoms/operaton-extensions/clients/go`",
    "rust": "`operaton-external-task-client` (crates.io)",
}
CLIENT_LANGUAGE = {"js": "TypeScript / JavaScript", "python": "Python", "go": "Go", "rust": "Rust"}
CLIENT_ORDER = ["js", "python", "go", "rust"]


def manifest(path):
    data = {}
    for line in open(path, encoding="utf-8"):
        m = re.match(r"^(\w+):\s*(.*)$", line.rstrip())
        if m:
            data[m.group(1)] = m.group(2).strip()
    for key in ("kind", "name", "title", "description", "compatibility", "status"):
        if not data.get(key):
            sys.exit(f"{path}: '{key}' is missing")
    data["compat"] = ", ".join(x.strip() for x in data["compatibility"].strip("[]").split(","))
    data["folder"] = os.path.relpath(os.path.dirname(path), ROOT)
    return data


def collect():
    rows = {"plugin": [], "connector": [], "client": []}
    for pattern in ("plugins/*/extension.yml", "connectors/*/extension.yml", "clients/*/extension.yml"):
        for path in sorted(glob.glob(os.path.join(ROOT, pattern))):
            m = manifest(path)
            rows[m["kind"]].append(m)
    return rows


def table(kind, rows):
    if kind == "client":
        out = ["| Language | Package | What it does | Operaton | Status |", "|---|---|---|---|---|"]
        for d in sorted(rows, key=lambda x: CLIENT_ORDER.index(x["name"]) if x["name"] in CLIENT_ORDER else 99):
            out.append(
                f"| [{CLIENT_LANGUAGE.get(d['name'], d['title'])}](../{d['folder']}/README.md) | "
                f"{CLIENT_PACKAGE.get(d['name'], '')} | {d['description']} | {d['compat']} | {d['status']} |"
            )
    else:
        out = ["| Extension | What it does | Operaton | Status |", "|---|---|---|---|"]
        for d in sorted(rows, key=lambda x: x["title"].lower()):
            out.append(f"| [{d['title']}](../{d['folder']}/README.md) | {d['description']} | {d['compat']} | {d['status']} |")
    return "\n".join(out)


def main():
    check = "--check" in sys.argv[1:]
    text = original = open(CATALOG, encoding="utf-8").read()
    rows = collect()
    for kind, group in (("plugin", "plugins"), ("connector", "connectors"), ("client", "clients")):
        begin, end = f"<!-- catalog:{group}:begin -->", f"<!-- catalog:{group}:end -->"
        pattern = re.compile(re.escape(begin) + r"\n.*?\n" + re.escape(end), re.S)
        if not pattern.search(text):
            sys.exit(f"docs/catalog.md: markers {begin} / {end} not found")
        text = pattern.sub(lambda _m: f"{begin}\n{table(kind, rows[kind])}\n{end}", text, count=1)
    if text == original:
        print("docs/catalog.md is up to date")
        return 0
    if check:
        print("docs/catalog.md is out of date: run tools/generate-catalog.py", file=sys.stderr)
        return 1
    open(CATALOG, "w", encoding="utf-8").write(text)
    print("docs/catalog.md updated")
    return 0


if __name__ == "__main__":
    sys.exit(main())
