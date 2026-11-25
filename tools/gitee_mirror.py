#!/usr/bin/env python3
"""Mirror a published GitHub Release to Gitee.

GitHub is the source of truth. This script downloads the release assets from
GitHub, verifies their checksums, then creates or reuses the matching Gitee
Release. The Gitee Release is pinned to the same source commit and only missing
assets are uploaded. Existing assets are verified and never overwritten.

The Gitee API token is read from TIKU_GITEE_TOKEN. Use a token owned by the
pchartming account; tokens from other projects must not be reused.

Examples:

    ./tools/gitee-mirror.sh check --tag v0.1.0
    ./tools/gitee-mirror.sh upload --tag v0.1.0 --expected-commit <sha> --dry-run
    ./tools/gitee-mirror.sh upload --tag v0.1.0 --expected-commit <sha>
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import tempfile
from pathlib import Path

try:
    import requests
except ImportError:
    print("error: requests is required (pip install requests)", file=sys.stderr)
    raise SystemExit(1)

OWNER = "pchartming"
REPO = "tiku-prompt"
GITHUB_REPO = "pchartming/tiku-prompt"
GITEE_API = "https://gitee.com/api/v5"
GITHUB_API = "https://api.github.com"
TOKEN_ENV = "TIKU_GITEE_TOKEN"
MAX_ASSET_BYTES = 100 * 1024 * 1024
EXPECTED_ASSETS = (
    "tiku-prompt-{tag}.apk",
    "tiku-prompt-{tag}-source.tar.gz",
    "SHA256SUMS",
    "release-info.txt",
)
CONTENT_TYPES = {
    ".apk": "application/vnd.android.package-archive",
    ".gz": "application/gzip",
    ".txt": "text/plain; charset=utf-8",
}


def fail(message: str) -> None:
    print(f"error: {message}", file=sys.stderr)
    raise SystemExit(1)


def warn(message: str) -> None:
    print(f"warning: {message}", file=sys.stderr)


def expected_assets(tag: str) -> tuple[str, ...]:
    return tuple(name.format(tag=tag) for name in EXPECTED_ASSETS)


def token() -> str:
    value = os.environ.get(TOKEN_ENV, "").strip()
    if not value:
        fail(
            f"{TOKEN_ENV} is not set; use a Gitee token owned by {OWNER}, "
            "not a token from another project"
        )
    return value


def session() -> requests.Session:
    s = requests.Session()
    s.headers.update({
        "User-Agent": "tiku-prompt-gitee-mirror/1",
        "Authorization": "token " + token(),
    })
    return s


def gitee_api(s: requests.Session, method: str, path: str, **kwargs) -> requests.Response:
    response = s.request(method, GITEE_API + path, timeout=kwargs.pop("timeout", 60), **kwargs)
    if response.status_code >= 400:
        # Never print request headers: they carry the token.
        detail = response.text[:300].replace("\n", " ")
        fail(f"Gitee API {method} {path}: HTTP {response.status_code} {detail}")
    return response


def github_get(path: str) -> dict | None:
    response = requests.get(
        GITHUB_API + path,
        headers={
            "Accept": "application/vnd.github+json",
            "User-Agent": "tiku-prompt-gitee-mirror/1",
            "X-GitHub-Api-Version": "2022-11-28",
        },
        timeout=120,
    )
    if response.status_code == 404:
        return None
    if response.status_code >= 400:
        fail(f"GitHub API GET {path}: HTTP {response.status_code}")
    return response.json()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_sums(path: Path) -> dict[str, str]:
    if not path.is_file():
        fail(f"missing {path}")
    sums: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        parts = raw.split()
        if len(parts) >= 2 and re.fullmatch(r"[0-9a-f]{64}", parts[0]):
            sums[parts[1].lstrip("*")] = parts[0]
    return sums


def download(url: str, destination: Path, expected_size: int | None = None) -> str:
    if not url.startswith("https://"):
        fail(f"refusing non-HTTPS asset URL: {url}")
    digest = hashlib.sha256()
    size = 0
    with requests.get(url, stream=True, timeout=600) as response:
        if response.status_code != 200:
            fail(f"asset download returned HTTP {response.status_code}")
        with destination.open("wb") as handle:
            for chunk in response.iter_content(1024 * 1024):
                if not chunk:
                    continue
                size += len(chunk)
                if size > MAX_ASSET_BYTES:
                    fail(f"{destination.name} exceeds the Gitee 100 MB per-asset limit")
                digest.update(chunk)
                handle.write(chunk)
    if expected_size is not None and size != expected_size:
        fail(f"{destination.name} downloaded {size} bytes, expected {expected_size}")
    return digest.hexdigest()


def github_tag_commit(tag: str) -> str:
    ref = github_get(f"/repos/{GITHUB_REPO}/git/ref/tags/{tag}")
    if not ref:
        fail(f"GitHub tag {tag} does not exist")
    obj = ref.get("object") or {}
    if obj.get("type") == "commit":
        return str(obj.get("sha") or "")
    if obj.get("type") == "tag":
        tag_object = github_get(f"/repos/{GITHUB_REPO}/git/tags/{obj.get('sha', '')}")
        if tag_object:
            target = tag_object.get("object") or {}
            if target.get("type") == "commit" and target.get("sha"):
                return str(target["sha"])
    fail(f"cannot resolve GitHub tag {tag} to a commit")


def github_release(tag: str) -> dict:
    release = github_get(f"/repos/{GITHUB_REPO}/releases/tags/{tag}")
    if not release:
        fail(f"GitHub release {tag} does not exist; publish it before mirroring to Gitee")
    if release.get("tag_name") != tag:
        fail(f"GitHub release tag mismatch: expected {tag}, got {release.get('tag_name')}")
    if release.get("draft"):
        fail(f"GitHub release {tag} is still a draft")
    return release


def verify_release_info(values: dict[str, str], tag: str, commit: str) -> None:
    required = {
        "tag": tag,
        "commit": commit,
        "package": "io.github.pchartming.tikuprompt",
        "apk": f"tiku-prompt-{tag}.apk",
        "source": f"tiku-prompt-{tag}-source.tar.gz",
    }
    for key, expected in required.items():
        actual = values.get(key, "")
        if actual != expected:
            fail(f"release-info.txt {key} is {actual!r}, expected {expected!r}")


def verify_github_release(tag: str, workdir: Path, expected_commit: str | None) -> tuple[dict, str, list[Path]]:
    release = github_release(tag)
    commit = github_tag_commit(tag)
    if expected_commit and commit != expected_commit:
        fail(f"GitHub tag {tag} resolves to {commit[:12]}, expected {expected_commit[:12]}")

    names = expected_assets(tag)
    assets = {asset.get("name"): asset for asset in release.get("assets", [])}
    missing = [name for name in names if name not in assets]
    if missing:
        fail(f"GitHub release {tag} is missing assets: {', '.join(missing)}")

    paths: list[Path] = []
    for name in names:
        asset = assets[name]
        size = asset.get("size")
        if not isinstance(size, int):
            fail(f"GitHub asset {name} has no size")
        destination = workdir / name
        digest = download(str(asset.get("browser_download_url") or ""), destination, size)
        github_digest = str(asset.get("digest") or "")
        if github_digest.startswith("sha256:") and github_digest[7:] != digest:
            fail(f"GitHub digest mismatch for {name}")
        paths.append(destination)

    sums = read_sums(workdir / "SHA256SUMS")
    for name in (f"tiku-prompt-{tag}.apk", f"tiku-prompt-{tag}-source.tar.gz"):
        wanted = sums.get(name)
        if not wanted:
            fail(f"SHA256SUMS is missing {name}")
        if sha256_file(workdir / name) != wanted:
            fail(f"checksum mismatch for GitHub asset {name}")

    info: dict[str, str] = {}
    for raw in (workdir / "release-info.txt").read_text(encoding="utf-8").splitlines():
        if "=" in raw:
            key, value = raw.split("=", 1)
            info[key.strip()] = value.strip()
    verify_release_info(info, tag, commit)
    return release, commit, paths


def get_repo(s: requests.Session) -> dict:
    user = gitee_api(s, "GET", "/user").json()
    login = str(user.get("login") or "")
    if login != OWNER:
        fail(f"{TOKEN_ENV} is not authorized for the {OWNER} Gitee account")
    data = gitee_api(s, "GET", f"/repos/{OWNER}/{REPO}").json()
    if data.get("private"):
        fail(f"Gitee repository {OWNER}/{REPO} is private")
    if not (data.get("permission") or {}).get("admin"):
        fail(f"no admin permission on Gitee repository {OWNER}/{REPO}")
    return data


def find_release(s: requests.Session, tag: str) -> dict | None:
    response = s.get(f"{GITEE_API}/repos/{OWNER}/{REPO}/releases/tags/{tag}", timeout=30)
    if response.status_code == 404:
        return None
    if response.status_code >= 400:
        fail(f"Gitee API release lookup: HTTP {response.status_code}")
    return response.json()


def release_target(release: dict) -> str:
    return str(release.get("target_commitish") or "").strip()


def check_target(release: dict, expected: str) -> None:
    target = release_target(release)
    if target != expected:
        fail(
            f"Gitee Release targets {target[:12] or 'nothing'}, expected {expected[:12]}; "
            "refusing to publish assets against a different source commit"
        )


def create_release(s: requests.Session, tag: str, commit: str, github: dict) -> dict:
    data = gitee_api(s, "POST", f"/repos/{OWNER}/{REPO}/releases", data={
        "tag_name": tag,
        "name": str(github.get("name") or f"Tiku Prompt {tag}"),
        "body": str(github.get("body") or ""),
        "target_commitish": commit,
        "prerelease": "true" if github.get("prerelease") else "false",
    }).json()
    print(f"release_created tag={data.get('tag_name')} id={data.get('id')}")
    check_target(data, commit)
    return data


def refresh_release(s: requests.Session, release_id: int) -> dict:
    return gitee_api(s, "GET", f"/repos/{OWNER}/{REPO}/releases/{release_id}").json()


def asset_map(release: dict) -> dict[str, dict]:
    return {str(asset.get("name")): asset for asset in release.get("assets", [])}


def verify_existing_asset(asset: dict, local: Path) -> None:
    name = local.name
    remote_size = asset.get("size")
    if remote_size is not None:
        try:
            remote_size = int(remote_size)
        except (TypeError, ValueError):
            fail(f"existing Gitee asset {name} has an invalid size")
        if remote_size != local.stat().st_size:
            fail(f"existing Gitee asset {name} has a different size; refusing to overwrite")
    url = str(asset.get("browser_download_url") or "")
    if not url:
        fail(f"existing Gitee asset {name} has no download URL")
    with tempfile.TemporaryDirectory(prefix="tiku-gitee-asset-") as tmp:
        remote = Path(tmp) / name
        download(url, remote, local.stat().st_size)
        if sha256_file(remote) != sha256_file(local):
            fail(f"existing Gitee asset {name} differs from GitHub; refusing to overwrite")
    print(f"skip_verified name={name}")


def upload_asset(s: requests.Session, release_id: int, path: Path) -> dict:
    url = f"{GITEE_API}/repos/{OWNER}/{REPO}/releases/{release_id}/attach_files"
    content_type = CONTENT_TYPES.get(path.suffix, "application/octet-stream")
    for attempt in range(1, 3):
        try:
            with path.open("rb") as handle:
                response = s.post(
                    url,
                    files={"file": (path.name, handle, content_type)},
                    timeout=600,
                )
            if response.status_code in (200, 201):
                data = response.json()
                print(f"uploaded name={path.name} bytes={path.stat().st_size}")
                return data
            warn(f"upload failed name={path.name} attempt={attempt} HTTP {response.status_code}")
        except requests.RequestException as exc:
            warn(f"upload error name={path.name} attempt={attempt}: {type(exc).__name__}")
    fail(f"could not upload {path.name}")


def verify_gitee_release(s: requests.Session, release: dict, paths: list[Path]) -> None:
    by_name = asset_map(release)
    missing = [path.name for path in paths if path.name not in by_name]
    if missing:
        fail(f"Gitee release is missing assets: {', '.join(missing)}")
    for path in paths:
        asset = by_name[path.name]
        url = str(asset.get("browser_download_url") or "")
        if not url:
            fail(f"Gitee asset {path.name} has no download URL")
        with tempfile.TemporaryDirectory(prefix="tiku-gitee-verify-") as tmp:
            remote = Path(tmp) / path.name
            download(url, remote, path.stat().st_size)
            if sha256_file(remote) != sha256_file(path):
                fail(f"anonymous Gitee download differs for {path.name}")
        print(f"verified name={path.name} bytes={path.stat().st_size}")


def cmd_check(args: argparse.Namespace) -> None:
    with tempfile.TemporaryDirectory(prefix="tiku-github-release-") as tmp:
        release, commit, paths = verify_github_release(args.tag, Path(tmp), args.expected_commit)
    print(
        f"github_release_ok tag={args.tag} commit={commit} "
        f"assets={len(paths)} name={release.get('name', '')!r}"
    )


def cmd_upload(args: argparse.Namespace) -> None:
    with tempfile.TemporaryDirectory(prefix="tiku-github-release-") as tmp:
        github, commit, paths = verify_github_release(args.tag, Path(tmp), args.expected_commit)
        if args.dry_run:
            print(f"dry_run github_assets_ok tag={args.tag} commit={commit} assets={len(paths)}")
        s = session()
        get_repo(s)
        release = find_release(s, args.tag)
        if release is None:
            if args.dry_run:
                print(f"dry_run would_create_release tag={args.tag} target={commit}")
                return
            release = create_release(s, args.tag, commit, github)
        else:
            print(
                f"release_exists tag={args.tag} id={release.get('id')} "
                f"target={release_target(release)}"
            )
            check_target(release, commit)

        existing = asset_map(release)
        for path in paths:
            if path.name in existing:
                verify_existing_asset(existing[path.name], path)
                continue
            if args.dry_run:
                print(f"dry_run would_upload name={path.name} bytes={path.stat().st_size}")
                continue
            upload_asset(s, int(release["id"]), path)

        release = refresh_release(s, int(release["id"]))
        check_target(release, commit)
        if args.dry_run:
            print("dry_run verify_skipped no_writes_made")
            return
        verify_gitee_release(s, release, paths)
        print(f"gitee_mirror_ok repo={OWNER}/{REPO} tag={args.tag} commit={commit} files={len(paths)}")


def cmd_status(args: argparse.Namespace) -> None:
    s = session()
    get_repo(s)
    release = find_release(s, args.tag) if args.tag else None
    if release is None and args.tag:
        print(f"no release for tag={args.tag}")
        return
    if release is None:
        releases = gitee_api(s, "GET", f"/repos/{OWNER}/{REPO}/releases").json()
        if not releases:
            print("no releases")
            return
        release = releases[0]
    if args.expected_commit:
        check_target(release, args.expected_commit)
    print(
        f"repo={OWNER}/{REPO} tag={release.get('tag_name')} id={release.get('id')} "
        f"target={release_target(release)}"
    )
    for asset in release.get("assets", []):
        print(f"asset name={asset.get('name')} url={asset.get('browser_download_url')}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    p_check = sub.add_parser("check", help="verify the GitHub release and assets (no token)")
    p_check.add_argument("--tag", required=True)
    p_check.add_argument("--expected-commit", default=None)
    p_check.set_defaults(func=cmd_check)

    p_upload = sub.add_parser("upload", help="create/reuse the Gitee release and upload missing assets")
    p_upload.add_argument("--tag", required=True)
    p_upload.add_argument("--expected-commit", default=None)
    p_upload.add_argument("--dry-run", action="store_true")
    p_upload.set_defaults(func=cmd_upload)

    p_status = sub.add_parser("status", help="show a Gitee release (requires TIKU_GITEE_TOKEN)")
    p_status.add_argument("--tag", default=None)
    p_status.add_argument("--expected-commit", default=None)
    p_status.set_defaults(func=cmd_status)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()