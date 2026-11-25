# Publishing workflow

The **private upstream `origin` is the default publication target**. GitHub and
Gitee are opt-in destinations: the scripts refuse them unless an explicit
authorization flag is supplied, and no remote is contacted by `check`.

The process is deliberately boring: review, scan, test, dry-run, push without
force. The scripts do not create tags, publish APKs, create repositories, or
change repository visibility.

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

Local-only configuration is ignored by Git:

- `.env.build` — remote-builder paths and SSH target
- `.publish-denylist.txt` — additional private identifiers to reject

Never force-add either file.

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
available. The scan is a guardrail, not a proof: review the diff and any new
data manually.

## Private push (default)

1. Build and test the snapshot you intend to publish:

   ```bash
   ./gradlew :app:testDebugUnitTest
   ./gradlew :app:assembleDebug
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
./tools/publish.sh push-github --authorize-github --visibility private
```

Then execute only after reviewing the output and confirming the target:

```bash
./tools/publish.sh push-github --authorize-github --visibility private --execute
```

A public release is a second, separate decision:

1. Confirm the repository contents, history, and notices are acceptable.
2. Change the GitHub repository visibility to public manually.
3. Run a final dry-run with `--visibility public --authorize-github`.
4. Push with explicit public confirmation and gitleaks installed:

   ```bash
   ./tools/publish.sh push-github --authorize-github --visibility public \
     --execute --confirm-public
   ```

The confirmation flags do not themselves make a repository public. Do not
publish if the private review found unresolved data-rights, attribution,
identity, or security questions. If the local clean history diverges from the
GitHub repository's existing commits, do not force-push; resolve the repository
or branch choice explicitly first.

## Gitee mirror (explicit authorization only)

Gitee is an optional source mirror. Do not configure or push it until the
repository owner explicitly authorizes the mirror. Configure the `gitee` remote
first, then:

```bash
./tools/publish.sh push-gitee --authorize-gitee
./tools/publish.sh push-gitee --authorize-gitee --execute --confirm-mirror
```

This mirrors the source `main` branch only. It does not create or update Gitee
Releases, tags, binaries, or repository descriptions.

## APK and release artifacts

The publication scripts intentionally do not publish APKs. A debug-signed APK is
not a release artifact. If binaries are published later, build them from the
reviewed commit, sign them with a release key that is never committed, and
publish a SHA-256 checksum next to each file. Document the signing key owner and
rotation procedure separately.
