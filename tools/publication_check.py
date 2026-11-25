#!/usr/bin/env python3
"""Fail-closed checks for publishing the Tiku Prompt source tree.

The check is intentionally dependency-free. It inspects the committed tree (or
the working tree on request), looks for credentials and identifying data, and
verifies the licence/notice files and bundled data boundaries. It never prints
matched secret values; findings are reported as file, line, and category.

This is a guardrail, not a substitute for a human licence review.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

REQUIRED_FILES = {
    ".gitignore",
    "LICENSE",
    "NOTICE",
    "README.md",
    "THIRD-PARTY-NOTICES.md",
    "app/build.gradle.kts",
    "app/src/main/AndroidManifest.xml",
}

FORBIDDEN_SUFFIXES = (
    ".aab",
    ".apk",
    ".idsig",
    ".iml",
    ".jks",
    ".keystore",
    ".p12",
    ".pfx",
)

FORBIDDEN_BASENAMES = {
    ".DS_Store",
    "credentials",
    "id_dsa",
    "id_ecdsa",
    "id_ed25519",
    "id_rsa",
    "local.properties",
    "secrets.properties",
}

MAX_TEXT_BYTES = 8 * 1024 * 1024

# Regexes are kept free of usable secret values. The sensitive-comment words are
# unicode escapes so that this scanner does not flag its own source text.
SECRET_PATTERNS = (
    ("private-key-block", re.compile(r"-----BEGIN (?:RSA |OPENSSH |EC |DSA )?PRIVATE KEY-----")),
    ("github-token", re.compile(r"\bgh[pousr]_[A-Za-z0-9]{30,}\b")),
    ("github-fine-grained-token", re.compile(r"\bgithub_pat_[A-Za-z0-9_]{40,}\b")),
    ("aws-access-key", re.compile(r"\b(?:AKIA|ASIA)[0-9A-Z]{16}\b")),
    ("aliyun-access-key", re.compile(r"\bLTAI[0-9A-Za-z]{12,}\b")),
    ("slack-token", re.compile(r"\bxox[baprs]-[A-Za-z0-9-]{20,}\b")),
    ("google-api-key", re.compile(r"\bAIza[0-9A-Za-z_-]{35}\b")),
    ("stripe-live-key", re.compile(r"\bsk_live_[0-9A-Za-z]{20,}\b")),
    ("url-credentials", re.compile(r"https?://[^/\s:@]+:[^/\s@]+@")),
    ("bearer-token", re.compile(r"\bAuthorization\s*:\s*Bearer\s+[A-Za-z0-9._~+/=-]{20,}", re.I)),
    (
        "credential-assignment",
        re.compile(
            r"""(?ix)
            \b(?:api[_-]?key|apikey|secret|client[_-]?secret|access[_-]?token|
                 auth[_-]?token|password|passwd|access[_-]?key)\b
            \s*[:=]\s*
            ["']([A-Za-z0-9_./+=-]{12,})["']
            """
        ),
    ),
)

EMAIL_RE = re.compile(
    r"(?<![\w.+-])([A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,})(?![\w.-])"
)
PRIVATE_IPV4_RE = re.compile(
    r"\b(?:10\.\d{1,3}\.\d{1,3}\.\d{1,3}|"
    r"192\.168\.\d{1,3}\.\d{1,3}|"
    r"172\.(?:1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3})\b"
)
HOME_PATH_RE = re.compile(r"(?<![\w])/(?:home|Users)/[A-Za-z0-9._-]+")
COMMENT_LINE_RE = re.compile(r"^\s*(?://|#|/\*|\*|<!--|--|;)")
SENSITIVE_COMMENT_RE = re.compile(
    "|".join(
        [
            "逆向",          # reverse engineering
            "反编译",    # decompilation
            "破解",          # cracking
            "盗版",          # piracy
            "爬虫",          # scraping
            "绕过",          # bypassing
            "内部资料",  # internal material
            "不要公开",  # do not publish
            "保密",          # confidential
            "confidential",
            "do not publish",
        ]
    ),
    re.I,
)
TODO_RE = re.compile(r"\b(?:TODO|FIXME|HACK|XXX)\b", re.I)

ALLOWED_EMAIL_SUFFIXES = (
    "@example.com",
    "@example.org",
    "@users.noreply.github.com",
)
ALLOWED_EMAILS = {
    "git@github.com",
    "git@gitee.com",
}
ALLOWED_CATALOG_SOURCE = "https://basic.smartedu.cn/syncClassroom"


@dataclass(frozen=True)
class Finding:
    severity: str  # error | warning
    category: str
    path: str
    line: int = 0


class Checker:
    def __init__(self, root: Path, ref: str | None, use_denylist: Path | None):
        self.root = root
        self.ref = ref
        self.findings: list[Finding] = []
        self.denylist: list[str] = []
        self.files: dict[str, bytes | None] = {}
        if use_denylist and use_denylist.is_file():
            for raw in use_denylist.read_text(encoding="utf-8").splitlines():
                term = raw.strip()
                if term and not term.startswith("#"):
                    self.denylist.append(term.casefold())

    def add(self, severity: str, category: str, path: str, line: int = 0) -> None:
        self.findings.append(Finding(severity, category, path, line))

    def fail(self, category: str, path: str, line: int = 0) -> None:
        self.add("error", category, path, line)

    def warn(self, category: str, path: str, line: int = 0) -> None:
        self.add("warning", category, path, line)

    def git(self, *args: str, binary: bool = False):
        return subprocess.run(
            ["git", "-C", str(self.root), *args],
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=not binary,
        ).stdout

    def load_files(self) -> None:
        if self.ref:
            raw = self.git("ls-tree", "-r", "--name-only", "-z", self.ref, binary=True)
            paths = [p.decode("utf-8", "surrogateescape") for p in raw.split(b"\0") if p]
            for path in paths:
                try:
                    self.files[path] = self.git("show", f"{self.ref}:{path}", binary=True)
                except subprocess.CalledProcessError:
                    self.files[path] = None
        else:
            raw = self.git(
                "ls-files", "-z", "--cached", "--others", "--exclude-standard", binary=True
            )
            paths = [p.decode("utf-8", "surrogateescape") for p in raw.split(b"\0") if p]
            for path in paths:
                full = self.root / path
                if full.is_file() and not full.is_symlink():
                    self.files[path] = full.read_bytes()
                elif full.is_symlink():
                    self.files[path] = None
                else:
                    # Deleted but still present in the index: not publishable.
                    self.files.pop(path, None)

    def path_checks(self) -> None:
        for path in sorted(self.files):
            p = Path(path)
            lowered = path.casefold()
            name = p.name
            if name in FORBIDDEN_BASENAMES:
                self.fail("forbidden-file", path)
            if name.startswith(".env") and not name.endswith((".example", ".sample")):
                self.fail("forbidden-file", path)
            if name.endswith(FORBIDDEN_SUFFIXES):
                self.fail("forbidden-binary", path)
            parts = p.parts
            if any(part in {"dist", ".gradle", ".idea", "build", "logs"} for part in parts[:-1]):
                self.fail("forbidden-build-path", path)
            if "tools" in parts and "logs" in parts:
                self.fail("forbidden-build-path", path)
            data = self.files[path]
            if data is not None and len(data) > MAX_TEXT_BYTES:
                self.fail("oversized-file", path)

            for idx, term in enumerate(self.denylist, 1):
                if term in lowered:
                    self.fail(f"denylist-path #{idx}", path)

    def text_checks(self) -> None:
        for path, data in sorted(self.files.items()):
            if data is None:
                continue
            if b"\0" in data[:8192]:
                continue
            if len(data) > MAX_TEXT_BYTES:
                continue
            text = data.decode("utf-8", "replace")
            for lineno, line in enumerate(text.splitlines(), 1):
                for label, pattern in SECRET_PATTERNS:
                    if pattern.search(line):
                        self.fail(f"secret:{label}", path, lineno)
                for match in EMAIL_RE.finditer(line):
                    email = match.group(1).casefold()
                    if email not in ALLOWED_EMAILS and not email.endswith(ALLOWED_EMAIL_SUFFIXES):
                        self.fail("personal-email", path, lineno)
                if PRIVATE_IPV4_RE.search(line):
                    self.fail("private-ip", path, lineno)
                if HOME_PATH_RE.search(line):
                    self.fail("home-path", path, lineno)
                folded = line.casefold()
                for idx, term in enumerate(self.denylist, 1):
                    if term in folded:
                        self.fail(f"denylist-content #{idx}", path, lineno)
                if COMMENT_LINE_RE.search(line):
                    if SENSITIVE_COMMENT_RE.search(line):
                        self.fail("sensitive-comment", path, lineno)
                    if TODO_RE.search(line):
                        self.warn("todo-comment", path, lineno)

    def required_files(self) -> None:
        present = set(self.files)
        for required in sorted(REQUIRED_FILES - present):
            self.fail("missing-required-file", required)
        license_text = self.text_of("LICENSE")
        if license_text and "MIT License" not in license_text:
            self.fail("license-not-mit", "LICENSE")
        notice = self.text_of("NOTICE")
        if notice and "PlanYourTV" not in notice:
            self.fail("notice-missing-catalog-attribution", "NOTICE")
        third = self.text_of("THIRD-PARTY-NOTICES.md")
        for name in ("PlanYourTV", "StudyOnTV", "学习锁"):
            if third and name not in third:
                self.fail("third-party-notice-incomplete", "THIRD-PARTY-NOTICES.md")

    def text_of(self, path: str) -> str:
        data = self.files.get(path)
        if data is None or b"\0" in data[:8192]:
            return ""
        return data.decode("utf-8", "replace")

    def catalog_checks(self) -> None:
        catalog_path = "app/src/main/assets/quiz/unit_catalog.json"
        text = self.text_of(catalog_path)
        if not text:
            self.fail("missing-catalog", catalog_path)
            return
        try:
            catalog = json.loads(text)
        except json.JSONDecodeError as exc:
            self.fail("invalid-catalog-json", catalog_path, exc.lineno)
            return
        source = str(catalog.get("source", ""))
        if source != ALLOWED_CATALOG_SOURCE:
            self.fail("catalog-source-not-allowed", catalog_path)
        if not catalog.get("books"):
            self.fail("empty-catalog", catalog_path)

        prompt_dir = "app/src/main/assets/prompts/"
        prompts = sorted(p for p in self.files if p.startswith(prompt_dir) and p.endswith(".txt"))
        if not prompts:
            self.fail("missing-prompt-specs", prompt_dir)
        for path in prompts:
            prompt = self.text_of(path)
            if not prompt.rstrip().endswith("出题要求："):
                self.fail("prompt-heading-not-last", path)

    def manifest_check(self) -> None:
        path = "app/src/main/AndroidManifest.xml"
        manifest = self.text_of(path)
        if "android.permission.INTERNET" in manifest:
            self.fail("internet-permission", path)

    def history_check(self) -> None:
        if not self.ref:
            return
        raw = self.git("log", "--format=%ae%x00%ce%x00%B%x00", self.ref, binary=False)
        allowed_email = re.compile(
            r"^(?:[0-9]+\+[A-Za-z0-9-]+@users\.noreply\.github\.com|noreply@github\.com)$",
            re.I,
        )
        for value in raw.split("\0"):
            value = value.strip()
            if not value:
                continue
            if "@" in value and "\n" not in value:
                email = value.casefold()
                if not allowed_email.match(email):
                    self.fail("history-email-not-noreply", "<git-history>")
            else:
                for idx, term in enumerate(self.denylist, 1):
                    if term in value.casefold():
                        self.fail(f"history-denylist #{idx}", "<git-history>")

    def run(self) -> int:
        self.load_files()
        self.path_checks()
        self.text_checks()
        self.required_files()
        self.catalog_checks()
        self.manifest_check()
        self.history_check()

        errors = [f for f in self.findings if f.severity == "error"]
        warnings = [f for f in self.findings if f.severity == "warning"]
        for finding in errors:
            location = finding.path
            if finding.line:
                location += f":{finding.line}"
            print(f"[FAIL] {location} ({finding.category})")
        for finding in warnings:
            location = finding.path
            if finding.line:
                location += f":{finding.line}"
            print(f"[WARN] {location} ({finding.category})")
        print(
            f"\nscanned {len(self.files)} files; "
            f"{len(errors)} error(s), {len(warnings)} warning(s)"
        )
        if not errors:
            print("publication check passed")
        return 1 if errors else 0


def discover_root() -> Path:
    try:
        out = subprocess.run(
            ["git", "rev-parse", "--show-toplevel"],
            check=True,
            stdout=subprocess.PIPE,
            text=True,
        ).stdout.strip()
        return Path(out)
    except (subprocess.CalledProcessError, FileNotFoundError):
        return Path(__file__).resolve().parent.parent


def main(argv: Iterable[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--ref",
        default=None,
        help="committed ref to inspect (default: tracked and untracked working-tree files)",
    )
    parser.add_argument(
        "--history",
        action="store_true",
        help="also reject non-GitHub-noreply authors in the ref's history",
    )
    parser.add_argument(
        "--denylist",
        type=Path,
        default=None,
        help="optional local denylist file (ignored by Git); never publish it",
    )
    parser.add_argument(
        "--root",
        type=Path,
        default=None,
        help="repository root (default: current Git root)",
    )
    args = parser.parse_args(list(argv) if argv is not None else None)

    root = (args.root or discover_root()).resolve()
    use_denylist = args.denylist
    if use_denylist is None:
        candidate = root / ".publish-denylist.txt"
        if candidate.is_file():
            use_denylist = candidate
    checker = Checker(root, args.ref if args.history or args.ref else None, use_denylist)
    if args.history:
        checker.ref = args.ref or "HEAD"
    return checker.run()


if __name__ == "__main__":
    sys.exit(main())