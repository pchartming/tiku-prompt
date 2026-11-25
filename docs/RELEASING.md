# Release workflow

This document covers the signed Android APK and the matching source archive.
Source publication is handled separately by [PUBLISHING.md](PUBLISHING.md): the
private upstream is the default, and GitHub/Gitee require explicit owner
authorization.

`tools/release.sh` is the standard entry point. It builds the release APK on the
configured remote builder, signs it locally with a release key that is never
committed, verifies the result, and prepares checksummed artifacts. The
`publish-github` action is a separate opt-in step that pushes a tag and creates a
GitHub Release. `publish-gitee` mirrors an already-published GitHub Release;
GitHub remains the source of truth. Nothing is published by the default action.

## Release rules

- A debug APK is never a release artifact.
- Build only from a clean `main` whose `HEAD` is the reviewed commit.
- The tag is `v<versionName>`, for example `v0.1.0`; it must match
  `versionName` in `app/build.gradle.kts`.
- `versionCode` must increase for every published APK.
- Release notes are required at `docs/releases/<tag>.md`.
- The signing keystore is never committed to the published `main` branch. Keep
  the working copy outside the repository. A backup may live only on a
  private-only branch that is never pushed to GitHub/Gitee. Passwords are
  supplied by environment or a gitignored `.env.release`; a private backup of
  that file must stay on the same private-only branch.
- The script pins the signer certificate fingerprint. A debug certificate or a
  different certificate is rejected.
- Existing releases and tags are never overwritten. There is no force push.
- Gitee is a mirror, not an independent release source. Its `main` only
  fast-forwards; its tag is only created when missing; its Release assets must
  be byte-identical to the already-published GitHub Release.
- The APK is verified for package, version, zip alignment, non-debuggable
  status, a single signer, and absence of `INTERNET`.
- The source archive is produced with `git archive` from the same `HEAD` and
  is checked against a fresh archive before publishing.
- Release metadata is checked against the current tag, version, commit, signer,
  and checksums before publishing; stale artifacts are rejected.

## One-time setup

### Signing key

Generate a dedicated release key once, or use an existing one. Do not use the
Android debug key. The key owner is responsible for backups and rotation.

```bash
mkdir -p "$HOME/keys"
chmod 700 "$HOME/keys"
keytool -genkeypair \
  -keystore "$HOME/keys/tiku-prompt-release.jks" \
  -storetype PKCS12 \
  -alias tiku-prompt \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=Tiku Prompt Release, O=Tiku Prompt, C=CN"
```

The command prompts for the keystore and key passwords; do not put passwords on
the command line. Back up the keystore and passwords in controlled private
storage; never merge that backup into `main`. Losing the key means future APKs
cannot update an existing installation under the same application ID.

Read the certificate fingerprint; it is the value for
`TIKU_RELEASE_CERT_SHA256`. Colons and uppercase hex are accepted.

```bash
keytool -list -v \
  -keystore "$HOME/keys/tiku-prompt-release.jks" \
  -alias tiku-prompt | sed -n 's/.*SHA256: //p'
```

### Local configuration

```bash
cp .env.release.example .env.release
chmod 600 .env.release
```

Fill in the keystore path, alias, passwords, and certificate fingerprint. The
file is ignored by Git. Alternatively export the same variables in the shell.
Keep passwords out of command history and shell arguments.

The remote build configuration is `.env.build` (also ignored); see
[README.md](../README.md). The release build uses that remote builder
and does not run Gradle locally. The current release certificate fingerprint is
recorded in [RELEASE-KEY.md](RELEASE-KEY.md).

### GitHub release token

Only needed for `publish-github`. Export `GH_TOKEN` in the shell rather than
writing it to a file. The token must belong to the repository owner
`pchartming`; the script calls the GitHub API and rejects any other account. Use
a token scoped to this repository with permission to create releases and tags.
Never commit or paste the token into an issue, release note, or log.

The GitHub remote must already exist and its `main` must point at the same
commit. The script verifies both and does not push source code.

### Gitee release token

Only needed for `publish-gitee`. Create a Gitee personal access token owned by
the `pchartming` account with permission to read the repository and manage
Releases. Export it as `TIKU_GITEE_TOKEN` in the shell or keep it in the
gitignored `.env.release` with mode `600`. Do not reuse a token from another
project or account. The script verifies the token's login and refuses any other
account; it never prints the token or writes it to the repository.

## Version and notes

1. Update `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Add `docs/releases/v<versionName>.md`.
3. Commit both changes on `main`.
4. Run the tests and the source publication check.

The tag is derived from `versionName`; `--tag vX.Y.Z` is available for checking
a tag explicitly, but it must still match `versionName`.

## Build

```bash
./tools/release.sh check
./tools/release.sh build
```

`check` verifies a clean `main`, version/tag consistency, release notes, the
local toolchain, keystore access, and the publication scan. `build` repeats
those checks, runs the remote Gradle build and tests, signs the unsigned APK
locally, verifies it, and creates the release directory. It also checks that
`release-info.txt` matches `HEAD` and that the source archive reproduces from
`HEAD`.

Outputs are placed in `dist/release/<tag>/` (ignored by Git):

| File | Purpose |
| --- | --- |
| `tiku-prompt-<tag>.apk` | Signed release APK |
| `tiku-prompt-<tag>-source.tar.gz` | Source archive from `git archive HEAD` |
| `SHA256SUMS` | APK and source archive checksums |
| `release-info.txt` | Tag, commit, package, version, signer, permissions, hashes |

The APK signature is embedded in the APK; a v4 `.idsig` sidecar is not part of
the release and is removed after signing.

Inspect `release-info.txt` before publishing. Install the APK on a test device
and confirm the app opens offline. Verify the files:

```bash
cd "dist/release/v0.1.0"
sha256sum -c SHA256SUMS
```

The build step does not create a tag or contact GitHub.

## Publish the source

The source must be published before the GitHub release. Follow
[PUBLISHING.md](PUBLISHING.md). For the public GitHub repository this normally
means:

1. Run `./tools/publish.sh check` and review the result.
2. Dry-run the explicitly authorized GitHub push.
3. Run it with `--execute`.
4. Confirm `github/main` points at the release commit.

`tools/release.sh publish-github` refuses to continue when `github/main` is not
at local `HEAD`.

If the repository is still private and you intend to make it public, complete
the scan and public-mode dry-run **while it is still private**, then change the
visibility manually, then execute the push. Do not make a repository public
before the final scan. See [PUBLISHING.md](PUBLISHING.md) for the exact
commands.

## Publish the GitHub Release

The first invocation is a read-only dry run. It checks the assets, checksums,
signature, release metadata, source archive, GitHub account, source branch,
existing release, and tag; it does not create anything.

```bash
export GH_TOKEN='<token owned by pchartming>'
./tools/release.sh publish-github --authorize-github
```

After reviewing the output, execute:

```bash
./tools/release.sh publish-github --authorize-github --execute
```

The execute path:

1. Creates a local annotated tag if it is missing.
2. Pushes only that tag; no branch is pushed and no force is used.
3. Creates a temporary draft GitHub Release.
4. Uploads the APK, source archive, `SHA256SUMS`, and `release-info.txt`.
5. Publishes the draft only after every upload succeeds.

The publish step is not run for a debug build, an unsigned APK, a checksum
mismatch, a signer mismatch, or a dirty worktree. It does not change repository
visibility.

## Mirror the GitHub Release to Gitee

Gitee is only updated after the GitHub Release is published. The mirror script
downloads the four release assets from GitHub, verifies them against
`SHA256SUMS` and `release-info.txt`, fast-forwards Gitee `main` if needed,
creates the release tag only if it is missing, creates or reuses the Gitee
Release pinned to the same commit, and uploads only missing assets. Existing
Gitee assets are downloaded and compared; any difference aborts the mirror
instead of overwriting.

Dry-run first:

```bash
export TIKU_GITEE_TOKEN='<token owned by pchartming>'
./tools/release.sh publish-gitee --authorize-gitee
```

After reviewing the output, execute:

```bash
./tools/release.sh publish-gitee --authorize-gitee --execute --confirm-mirror
```

The script refuses to run if `github/main` or the GitHub tag is not at local
`HEAD`, if the GitHub Release or any expected asset is missing, if a Gitee tag
already points elsewhere, if Gitee `main` is not an ancestor, or if an existing
Gitee asset differs. It never force-pushes, moves or deletes a tag, or
overwrites a Release asset. A Gitee Release body and name are copied from the
GitHub Release.

## Verify the release

- Open the GitHub Release and confirm all four assets are present.
- Download the APK and source archive, then verify `SHA256SUMS`.
- Confirm the release body is `docs/releases/<tag>.md`.
- Confirm the APK signer fingerprint in `release-info.txt` matches the configured
  certificate.
- If mirrored, confirm the Gitee Release has the same tag/commit and all four
  assets; download them anonymously and verify they match `SHA256SUMS`.
- Keep the release tag immutable.

## Failure and retry

- If `build` fails, no tag or release has been created. Fix the problem and
  rebuild from a clean commit.
- If source publication fails, do not create the release first; resolve the
  source push and retry.
- If `publish-github` fails before tag creation, correct the error and rerun.
- If it fails after the tag is pushed but before the release is published,
  retry from the same commit. Do not move or force-push the tag. If a draft
  release was created, delete that draft through the GitHub UI before retrying;
  the script refuses to reuse an existing release.
- If `publish-gitee` fails after Gitee refs were pushed but before asset upload
  completed, rerun the same command from the same commit. The operation is
  idempotent: refs are no-ops and already-verified assets are skipped. Do not
  delete or rewrite tags or assets to force a retry.
- Do not delete or rewrite a published tag or release. Publish a new version
  instead.

## Key rotation

Existing installations are tied to the signing certificate. A new key requires
a new application identity or a user migration path; it cannot silently update
an APK signed by the old key. Record the certificate fingerprint with each
release and keep the old key backed up while its APKs are supported.
