# Publishing workflow

The **private upstream `origin` is the default publication target**. GitHub and
Gitee are opt-in destinations: the source scripts refuse them unless an explicit
authorization flag is supplied, and no remote is contacted by a plain `check`.

This document covers source publication. Signed APKs, tags, and GitHub Releases
are a separate, separately authorized flow in
[RELEASING.md](RELEASING.md). The source scripts never create tags, publish
APKs, create repositories, or change repository visibility.

The process is deliberately boring: review, scan, test, dry-run, push without
force.

## One-time setup

Confirm that `origin` is the private repository you intend to use:

```bash
git remote -v
```

The private remote must not be GitHub or Gitee; the script refuses that case so
a routine push cannot publish the project by accident.

Commit with a GitHub noreply identity, not a personal email:

```bash
git config user.name "pchartming"
git config user.email "<your-github-user-id>+pchartming@users.noreply.github.com"
```

### Public identity

Use only the project's public persona and GitHub noreply address. Do not use a
legal name, personal email, or another project's identity in public commits.
A Gitee mirror must use the same pseudonymous profile, publish only `main`, and
never import other branches, tags, or histories. Keep required third-party
attribution: a compatibility or privacy goal is not a reason to remove a
licence notice. State compatibility as unofficial once; do not add repeated
"no affiliation" or "no endorsement" disclaimers, which read as defensive.

Local-only configuration is ignored by Git:

- `.env.build` — remote-builder paths and SSH target
- `.env.release` — release keystore path and passwords; see
  [RELEASING.md](RELEASING.md)
- `.publish-denylist.txt` — additional private identifiers to reject

Never force-add these files.

## Checks

Run the built-in checks from the repository root:

```bash
./tools/publish.sh check
```

The check reads the committed tree and, with `--worktree`, also checks tracked
and untracked publishable files. It rejects:

- private keys, common provider tokens, token assignments, and credential-bearing URLs;
- personal email addresses (GitHub noreply addresses are allowed);
- private IPv4 addresses and absolute home-directory paths;
- local-only denylist terms, private host names, and private project identifiers;
- sensitive wording in comments;
- keystores, APKs/AABs, local properties, build output, and ignored environment files;
- missing `LICENSE`, `NOTICE`, or `THIRD-PARTY-NOTICES.md`;
- a missing MIT licence, incomplete third-party notices, or the `INTERNET` permission;
- a catalog source other than the public Smart Education Platform URL;
- prompt specs that do not end with `出题要求：`;
- non-GitHub-noreply author/committer emails in the checked history.

`gitleaks` is run automatically when available. Set `GITLEAKS_BIN=/path/to/gitleaks`
if it is not on `PATH`. A public GitHub push is refused unless gitleaks is
available. The release workflow also requires it. The scan is a guardrail, not a
proof: review the diff and any new data manually.

## Private push (default)

1. Build and test the snapshot you intend to publish with the remote builder:

   ```bash
   ./tools/build_remote.sh --warm --test
   ```

2. Commit on `main`. The worktree must be clean.
3. Run `./tools/publish.sh check`.
4. Review every change, especially the catalogue, target specs, and legal
   notices. Confirm there is no copied third-party code, image, question bank,
   or asset.
5. Dry-run the private push:

   ```bash
   ./tools/publish.sh push-private
   ```

6. Execute only after reviewing the output:

   ```bash
   ./tools/publish.sh push-private --execute
   ```

The push uses `HEAD:main` with no force. It refuses to run if the remote `main`
has commits that are not ancestors of the local `HEAD`. Running
`./tools/publish.sh` with no action is the same dry-run as `push-private`.

## GitHub publication (explicit authorization only)

Do not configure or push the GitHub remote until the repository owner explicitly
authorizes GitHub publication. The authorization flag is deliberately separate
from `--execute`; do not add it to routine private pushes.

After explicit authorization:

```bash
git remote add github git@github.com:pchartming/tiku-prompt.git
# If you use a dedicated SSH host alias, use that alias in the URL instead.
git remote -v
```

Run a dry-run first:

```bash
./tools/publish.sh push-github --authorize-github
```

Then execute only after reviewing the output and confirming the target:

```bash
./tools/publish.sh push-github --authorize-github --execute
```

### Making a private repository public safely

A repository's visibility is a separate owner decision. The scripts can check a
public-mode push but cannot change visibility, so the safe order is:

1. While the repository is still private, complete the review and run the full
   scan with gitleaks.
2. While it is still private, run the public-mode dry-run. This contacts GitHub
   only because `--authorize-github` was supplied, checks history and
   fast-forward status, and does **not** change visibility:

   ```bash
   ./tools/publish.sh check
   ./tools/publish.sh push-github --authorize-github --visibility public \
     --confirm-public
   ```

3. Only if those checks pass, change the GitHub repository visibility to public
   manually.
4. Push the already-reviewed commit with explicit public confirmation:

   ```bash
   ./tools/publish.sh push-github --authorize-github --visibility public \
     --execute --confirm-public
   ```

Do not make a repository public before the final scan. If the repository is
already public, repeat the scan and dry-run for every new commit, then execute
the push; no visibility change is needed.

The confirmation flags do not themselves make a repository public. Do not
publish if the review found unresolved data-rights, attribution, identity, or
security questions. If the local clean history diverges from the GitHub
repository's existing commits, do not force-push; resolve the repository or
branch choice explicitly first.

## Gitee mirror (explicit authorization only)

Gitee is an optional source mirror. The configured remote is
`git@gitee.com:pchartming/tiku-prompt.git`; the public repository uses `main` as
its default and only branch. Pushing still requires explicit authorization:

```bash
git remote add gitee git@gitee.com:pchartming/tiku-prompt.git  # one-time setup
```

Then run:

```bash
./tools/publish.sh push-gitee --authorize-gitee
./tools/publish.sh push-gitee --authorize-gitee --execute --confirm-mirror
```

This mirrors the source `main` branch only. It does not create or update Gitee
Releases, tags, binaries, or repository descriptions.

## APK and GitHub Release artifacts

Source publication and binary release are separate decisions. A debug-signed
APK is never a release artifact, and the source scripts do not upload binaries.

The standard signed-release flow is in [RELEASING.md](RELEASING.md). It uses
`tools/release.sh` to build from the reviewed commit, sign locally with a
keystore that is never committed to the published branch, verify the package and
certificate, and create a source archive with SHA-256 checksums. Publishing a tag
or GitHub Release requires the explicit `--authorize-github` flag in addition to
`--execute`.

Do not:
- upload a debug APK or an unsigned APK;
- overwrite an existing tag or release;
- put the keystore, passwords, or a GitHub token in the published source or any
  public remote;
- make a release from a dirty worktree or a commit that is not on the public
  source branch.
