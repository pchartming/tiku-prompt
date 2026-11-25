# Release signing key

This file records **public metadata only**. The release private key and its
passwords are not part of the published `main` branch or any public remote. A
private backup, if used, must stay isolated from public source history. Git
history is permanent and a signing key is a credential.

## Current key

| Field | Value |
| --- | --- |
| Key alias | `tiku-prompt` |
| Keystore | `$HOME/keys/tiku-prompt-release.jks` (working copy; not in public source) |
| Local config | `.env.release` (gitignored, mode 600) |
| Certificate subject | `CN=Tiku Prompt Release, OU=Android, O=Tiku Prompt, C=CN` |
| Certificate SHA-256 | `68110512534b70f4e2c14c8ace1372d96970bd5bf27156758e0afbd240e910ae` |
| Key algorithm | RSA 4096 |
| Validity | 10,000 days |
| Created | 2026-11-26 |

The fingerprint is pinned through `TIKU_RELEASE_CERT_SHA256`. APKs signed with a
different certificate, including the Android debug certificate, are rejected by
`tools/release.sh`.

## Verify the certificate

```bash
keytool -list -v \
  -keystore "$HOME/keys/tiku-prompt-release.jks" \
  -alias tiku-prompt
```

Look for the `SHA256:` line and compare it with the fingerprint above. The
keystore and `.env.release` must be backed up together in a controlled private
location, such as an offline password manager or a private-only backup branch
that is never pushed to GitHub/Gitee. Losing them prevents future APKs from
updating an installation under the same application ID.

## Do not

- commit the keystore, `.env.release`, or their passwords to `main` or any
  public remote;
- merge a private backup branch into `main`;
- upload the keystore to GitHub, a public issue, a release asset, or a build log;
- regenerate this key after publishing an APK; publish a new version or follow a
  deliberate migration instead.
