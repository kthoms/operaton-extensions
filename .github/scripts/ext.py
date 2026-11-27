#!/usr/bin/env python3
# Copyright the Operaton contributors.
# SPDX-License-Identifier: Apache-2.0
"""Helpers shared by the shell scripts in .github/scripts.

Only the Python standard library is used, so the scripts run on a plain runner and on a
developer machine alike.

Subcommands (all paths relative to the current directory, normally the repository root):

  manifest <ext> <key>          print a key of <ext>/extension.yml (lists comma-joined);
                                exit 3 when the key is missing
  manifest-check <ext>          validate <ext>/extension.yml; print one line per error
  pom-version <pom>             print project/version of a POM
  pom-property <pom> <name>     print a property of a POM
  compat-version <pom>          print the Operaton version pinned by a compatibility POM
  version <ext>                 print the version held by the extension's build file
                                (empty for build: go, whose version is its tag)
  version-at <ext> <ref>        the same, read from git object <ref>; exit 3 if no manifest
  set-version <ext> <version>   write <version> into the extension's POMs / package.json(+lock) /
                                pyproject.toml / Cargo.toml(+lock); nothing for go
  normalize <version>           stdin -> stdout with each whole occurrence of <version> replaced
                                by @VERSION@ (used to ignore release/snapshot version bumps)

The manifest is parsed with a small parser for its flat schema instead of PyYAML on purpose:
YAML would read `compatibility: [2.0, 2.10]` as floats and turn 2.10 into 2.1.
"""

import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

KINDS = {"plugins": "plugin", "connectors": "connector", "clients": "client"}
BUILDS = {"maven", "npm", "python", "go", "cargo"}
CLIENT_BUILDS = {"npm", "python", "go", "cargo"}
STATUSES = {"incubating", "stable", "deprecated"}
MINOR_RE = re.compile(r"^[0-9]+\.[0-9]+$")
NAME_RE = re.compile(r"^[a-z0-9]+(-[a-z0-9]+)*$")
REQUIRED_KEYS = ("kind", "name", "title", "description", "build", "compatibility", "since", "status")


def fail(msg, code=1):
    print(msg, file=sys.stderr)
    sys.exit(code)


# --------------------------------------------------------------------------- manifest

def _strip_comment(value):
    out, quote = [], None
    for i, ch in enumerate(value):
        if quote:
            if ch == quote:
                quote = None
        elif ch in "'\"":
            quote = ch
        elif ch == "#" and (i == 0 or value[i - 1] in " \t"):
            break
        out.append(ch)
    return "".join(out).strip()


def _unquote(value):
    value = value.strip()
    if len(value) >= 2 and value[0] == value[-1] and value[0] in "'\"":
        return value[1:-1]
    return value


def parse_manifest(path):
    """Parse the flat extension.yml schema: scalars, flow lists and block lists."""
    data, current_list = {}, None
    for lineno, raw in enumerate(Path(path).read_text(encoding="utf-8").splitlines(), 1):
        line = _strip_comment(raw.rstrip())
        if not line.strip() or line.strip() == "---":
            continue
        if line.lstrip().startswith("- ") or line.strip() == "-":
            if current_list is None:
                raise ValueError(f"{path}:{lineno}: list item without a key")
            data[current_list].append(_unquote(line.strip()[1:]))
            continue
        if line[0] in " \t":
            raise ValueError(f"{path}:{lineno}: nested structures are not part of the manifest schema")
        m = re.match(r"^([A-Za-z0-9_.-]+)\s*:\s*(.*)$", line)
        if not m:
            raise ValueError(f"{path}:{lineno}: cannot parse line: {raw}")
        key, value = m.group(1), m.group(2).strip()
        if key in data:
            raise ValueError(f"{path}:{lineno}: duplicate key '{key}'")
        current_list = None
        if value == "":
            data[key] = []
            current_list = key
        elif value.startswith("["):
            if not value.endswith("]"):
                raise ValueError(f"{path}:{lineno}: unterminated flow list")
            inner = value[1:-1].strip()
            data[key] = [_unquote(v) for v in inner.split(",")] if inner else []
        else:
            data[key] = _unquote(value)
    return data


def manifest_path(ext):
    return Path(ext) / "extension.yml"


def load_manifest(ext):
    path = manifest_path(ext)
    if not path.is_file():
        fail(f"{ext}: extension.yml not found", 2)
    try:
        return parse_manifest(path)
    except ValueError as e:
        fail(str(e), 2)


def cmd_manifest(ext, key):
    data = load_manifest(ext)
    if key not in data:
        sys.exit(3)
    value = data[key]
    print(",".join(value) if isinstance(value, list) else value)


def manifest_errors(ext):
    path = manifest_path(ext)
    if not path.is_file():
        return [f"{path}: missing (every extension has a manifest)"]
    try:
        data = parse_manifest(path)
    except ValueError as e:
        return [str(e)]
    errors = []
    parts = Path(ext).parts
    kinds_folder, folder = (parts[-2], parts[-1]) if len(parts) >= 2 else ("", parts[-1])
    for key in REQUIRED_KEYS:
        if key not in data or data[key] in ("", []):
            errors.append(f"{path}: '{key}' is missing or empty")
    kind, build = data.get("kind"), data.get("build")
    if kind and kind not in KINDS.values():
        errors.append(f"{path}: kind '{kind}' is not one of plugin, connector, client")
    elif kind and KINDS.get(kinds_folder) and KINDS[kinds_folder] != kind:
        errors.append(f"{path}: kind '{kind}' does not match folder '{kinds_folder}/' (expected '{KINDS[kinds_folder]}')")
    name = data.get("name")
    if name and name != folder:
        errors.append(f"{path}: name '{name}' must equal the folder name '{folder}'")
    if name and not NAME_RE.match(name):
        errors.append(f"{path}: name '{name}' is not lower-case kebab-case")
    if build and build not in BUILDS:
        errors.append(f"{path}: build '{build}' is not one of {', '.join(sorted(BUILDS))}")
    elif build and kind in ("plugin", "connector") and build != "maven":
        errors.append(f"{path}: a {kind} is built with maven, not '{build}'")
    elif build and kind == "client" and build not in CLIENT_BUILDS:
        errors.append(f"{path}: a client is built with npm, python, go or cargo, not '{build}'")
    compat = data.get("compatibility")
    if compat not in (None, "", []):
        if not isinstance(compat, list):
            errors.append(f"{path}: compatibility must be a list of Operaton minors, e.g. [2.1, 2.2]")
        else:
            for minor in compat:
                if not MINOR_RE.match(minor):
                    errors.append(f"{path}: compatibility entry '{minor}' is not a minor version (x.y)")
            if len(set(compat)) != len(compat):
                errors.append(f"{path}: compatibility lists a minor twice")
    since = data.get("since")
    if since and not (isinstance(since, str) and MINOR_RE.match(since)):
        errors.append(f"{path}: since '{since}' is not a minor version (x.y)")
    for key, value in data.items():
        values = value if isinstance(value, list) else [value]
        if any("TODO" in v for v in values):
            errors.append(f"{path}: '{key}' still contains a TODO placeholder")
    status = data.get("status")
    if status and status not in STATUSES:
        errors.append(f"{path}: status '{status}' is not one of {', '.join(sorted(STATUSES))}")
    return errors


def cmd_manifest_check(ext):
    errors = manifest_errors(ext)
    for e in errors:
        print(e)
    sys.exit(1 if errors else 0)


# --------------------------------------------------------------------------- POM

def _local(tag):
    return tag.rsplit("}", 1)[-1]


def _child(elem, name):
    for c in elem:
        if _local(c.tag) == name:
            return c
    return None


def _pom_root(pom):
    try:
        return ET.parse(pom).getroot()
    except (ET.ParseError, OSError) as e:
        fail(f"{pom}: cannot read POM: {e}", 2)


def pom_property(pom, name):
    props = _child(_pom_root(pom), "properties")
    if props is not None:
        p = _child(props, name)
        if p is not None and p.text:
            return p.text.strip()
    return None


def pom_version(pom):
    root = _pom_root(pom)
    v = _child(root, "version")
    if v is None:
        parent = _child(root, "parent")
        v = _child(parent, "version") if parent is not None else None
    return v.text.strip() if v is not None and v.text else None


def compat_version(pom):
    version = pom_property(pom, "operaton.version")
    if not version:
        dm = _child(_pom_root(pom), "dependencyManagement")
        deps = _child(dm, "dependencies") if dm is not None else None
        for d in (deps if deps is not None else []):
            a = _child(d, "artifactId")
            if a is not None and (a.text or "").strip() == "operaton-bom":
                v = _child(d, "version")
                version = (v.text or "").strip() if v is not None else None
    if not version or version.startswith("${"):
        fail(f"{pom}: no pinned Operaton version (property operaton.version or operaton-bom version)", 2)
    return version


# --------------------------------------------------------------------------- versions

def _toml_section_version(text, section):
    in_section = False
    for line in text.splitlines():
        s = line.strip()
        if s.startswith("["):
            in_section = s == f"[{section}]"
            continue
        if in_section:
            m = re.match(r'^version\s*=\s*["\']([^"\']+)["\']', s)
            if m:
                return m.group(1)
    return None


def _toml_section_set_version(text, section, version):
    out, in_section, done = [], False, False
    for line in text.splitlines(keepends=True):
        s = line.strip()
        if s.startswith("["):
            in_section = s == f"[{section}]"
        elif in_section and not done and re.match(r'^version\s*=', s):
            line = re.sub(r'(version\s*=\s*)(["\'])[^"\']*\2', lambda m: f"{m.group(1)}{m.group(2)}{version}{m.group(2)}", line, count=1)
            done = True
        out.append(line)
    if not done:
        return None
    return "".join(out)


def build_of(ext):
    data = load_manifest(ext)
    build = data.get("build")
    if build not in BUILDS:
        fail(f"{ext}: extension.yml has no valid 'build'", 2)
    return build


def project_version(ext):
    build = build_of(ext)
    ext = Path(ext)
    if build == "maven":
        return pom_version(ext / "pom.xml")
    if build == "npm":
        return json.loads((ext / "package.json").read_text(encoding="utf-8")).get("version")
    if build == "python":
        return _toml_section_version((ext / "pyproject.toml").read_text(encoding="utf-8"), "project")
    if build == "cargo":
        return _toml_section_version((ext / "Cargo.toml").read_text(encoding="utf-8"), "package")
    return ""  # go: the version is the tag


def project_version_at(ext, ref):
    """Like project_version, but reads the files from git object <ref> instead of the work tree."""
    import subprocess
    import tempfile

    def show(rel):
        r = subprocess.run(["git", "show", f"{ref}:{ext}/{rel}"], capture_output=True)
        return r.stdout if r.returncode == 0 else None

    with tempfile.TemporaryDirectory() as tmp:
        target = Path(tmp) / ext
        target.mkdir(parents=True)
        for rel in ("extension.yml", "pom.xml", "package.json", "pyproject.toml", "Cargo.toml"):
            content = show(rel)
            if content is not None:
                (target / rel).write_bytes(content)
        if not (target / "extension.yml").is_file():
            return None
        import os
        cwd = os.getcwd()
        os.chdir(tmp)
        try:
            return project_version(ext)
        finally:
            os.chdir(cwd)


_TOP_LEVEL_SECTIONS = re.compile(r"<(properties|dependencies|dependencyManagement|build|profiles|modules|reporting)\b")


def _maven_poms(ext):
    for pom in sorted(Path(ext).rglob("pom.xml")):
        if not any(part in ("target", "node_modules") for part in pom.relative_to(ext).parts):
            yield pom


def set_maven_version(ext, version):
    """Set the version of a Java extension in all its POMs, like versions:set but confined to the
    extension: the aggregator's own version, the <parent> version of every POM whose parent is the
    aggregator or one of its modules, a module's own explicit version and literal versions of
    dependencies on the extension's own artifacts. parent/ and the root POM are never touched."""
    aggregator = Path(ext) / "pom.xml"
    old = pom_version(aggregator)
    if not old:
        fail(f"{aggregator}: no version", 2)
    artifacts = set()
    for pom in _maven_poms(ext):
        a = _child(_pom_root(pom), "artifactId")
        if a is not None and a.text:
            artifacts.add(a.text.strip())
    old_tag = f"<version>{old}</version>"
    new_tag = f"<version>{version}</version>"
    for pom in _maven_poms(ext):
        text = pom.read_text(encoding="utf-8")
        original = text

        def fix_parent(m):
            block = m.group(0)
            aid = re.search(r"<artifactId>\s*([^<\s]+)\s*</artifactId>", block)
            if aid and aid.group(1) in artifacts:
                block = block.replace(old_tag, new_tag)
            return block

        text = re.sub(r"<parent>.*?</parent>", fix_parent, text, count=1, flags=re.S)
        # the project's own <version>: the first one after </parent> (or <project>), before any section
        start = text.find("</parent>")
        start = start + len("</parent>") if start >= 0 else text.find("<project")
        section = _TOP_LEVEL_SECTIONS.search(text, start)
        end = section.start() if section else len(text)
        pos = text.find(old_tag, start, end)
        if pos >= 0:
            text = text[:pos] + new_tag + text[pos + len(old_tag):]

        def fix_dependency(m):
            block = m.group(0)
            aid = re.search(r"<artifactId>\s*([^<\s]+)\s*</artifactId>", block)
            if aid and aid.group(1) in artifacts:
                block = block.replace(old_tag, new_tag)
            return block

        text = re.sub(r"<dependency>.*?</dependency>", fix_dependency, text, flags=re.S)
        if text != original:
            pom.write_text(text, encoding="utf-8")
    if pom_version(aggregator) != version:
        fail(f"{aggregator}: could not set the version to {version}", 2)


def set_version(ext, version):
    build = build_of(ext)
    ext = Path(ext)
    if build == "maven":
        set_maven_version(ext, version)
    elif build == "npm":
        for name in ("package.json", "package-lock.json"):
            f = ext / name
            if not f.is_file():
                continue
            data = json.loads(f.read_text(encoding="utf-8"))
            data["version"] = version
            if name == "package-lock.json" and "" in data.get("packages", {}):
                data["packages"][""]["version"] = version
            f.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    elif build in ("python", "cargo"):
        f = ext / ("pyproject.toml" if build == "python" else "Cargo.toml")
        section = "project" if build == "python" else "package"
        text = _toml_section_set_version(f.read_text(encoding="utf-8"), section, version)
        if text is None:
            fail(f"{f}: no version in [{section}]", 2)
        f.write_text(text, encoding="utf-8")
        if build == "cargo":
            lock = ext / "Cargo.lock"
            if lock.is_file():
                crate = None
                m = re.search(r'^\[package\][^\[]*?^name\s*=\s*"([^"]+)"', f.read_text(encoding="utf-8"), re.M | re.S)
                crate = m.group(1) if m else None
                if crate:
                    text = re.sub(r'(\[\[package\]\]\nname = "' + re.escape(crate) + r'"\nversion = ")[^"]*(")',
                                  lambda mm: mm.group(1) + version + mm.group(2), lock.read_text(encoding="utf-8"))
                    lock.write_text(text, encoding="utf-8")
    elif build == "go":
        pass  # nothing to write: the tag is the version
    else:
        fail(f"{ext}: unknown build '{build}'", 2)


def main(argv):
    if not argv:
        fail(__doc__)
    cmd, args = argv[0], argv[1:]
    if cmd == "manifest" and len(args) == 2:
        cmd_manifest(*args)
    elif cmd == "manifest-check" and len(args) == 1:
        cmd_manifest_check(args[0])
    elif cmd == "pom-version" and len(args) == 1:
        v = pom_version(args[0])
        if not v:
            fail(f"{args[0]}: no version", 2)
        print(v)
    elif cmd == "pom-property" and len(args) == 2:
        v = pom_property(*args)
        if v is None:
            sys.exit(3)
        print(v)
    elif cmd == "compat-version" and len(args) == 1:
        print(compat_version(args[0]))
    elif cmd == "version" and len(args) == 1:
        v = project_version(args[0])
        if v is None:
            fail(f"{args[0]}: no version found in the build file", 2)
        print(v)
    elif cmd == "version-at" and len(args) == 2:
        v = project_version_at(*args)
        if v is None:
            sys.exit(3)
        print(v)
    elif cmd == "set-version" and len(args) == 2:
        set_version(*args)
    elif cmd == "normalize" and len(args) == 1:
        text = sys.stdin.read()
        if args[0]:
            # whole version strings only: 1.0.0 must not match inside 11.0.0 or 1.0.0-SNAPSHOT
            text = re.sub(r"(?<![0-9A-Za-z.+-])" + re.escape(args[0]) + r"(?![0-9A-Za-z.+-])", "@VERSION@", text)
        sys.stdout.write(text)
    else:
        fail(__doc__)


if __name__ == "__main__":
    main(sys.argv[1:])
