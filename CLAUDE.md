# CLAUDE.md

本文件是给在此仓库工作的 AI 和维护者使用的硬性操作规则，用来防止误发布、误推公开远端和泄露签名凭据。具体步骤以 [docs/PUBLISHING.md](docs/PUBLISHING.md) 和 [docs/RELEASING.md](docs/RELEASING.md) 为准；本文件的禁止项优先。遇到未覆盖的情况先停下确认，不要自行推断授权。

## 项目边界

- 这是一个完全离线的 Android AI 提示词生成器，不调用模型、不需要 API Key，`AndroidManifest.xml` 中不得出现 `INTERNET` 权限。
- 应用包名/命名空间/applicationId 为 `io.github.pchartming.tikuprompt`。
- 不复制 StudyOnTV、PlanYourTV、学习锁等第三方 App 的源码、资源、题库或未公开材料；兼容性说明必须标明为非官方。
- 公开产物不得包含个人邮箱、绝对家目录路径、私有网络地址、私有主机名或内部项目标识。

## 远端与默认推送规则

默认只推私有的 `origin`。`github` 仅用于 `pchartming/tiku-prompt` 的公开源码和显式授权的 GitHub Release；Gitee 未配置，且必须单独授权。

- 普通提交、日常同步、文档更新：只推私仓 `origin`。
- 用户说“发布”“push”“上线”“部署”等模糊表述，不等于授权 GitHub 或 Gitee。必须由所有者明确指定目标平台后，才能使用对应的授权参数。
- 永远不要 force push、改写已发布历史、覆盖 tag/release、修改仓库可见性、创建或删除远端仓库。
- 不要把 `main` 以外的分支推到公开远端。不要使用 `git push --all`、`git push --mirror`、仓库导入或镜像同步。
- 提交身份使用 GitHub noreply 邮箱，不使用个人邮箱。

## 唯一标准入口

源码发布：

```bash
./tools/publish.sh check
./tools/publish.sh push-private --execute
```

GitHub 源码发布（仅在所有者明确授权后；当前仓库为公开）：

```bash
./tools/publish.sh push-github --authorize-github --visibility public --confirm-public
./tools/publish.sh push-github --authorize-github --visibility public --execute --confirm-public
```

Gitee 同步（默认不执行；仅在明确授权且远端已配置后）：

```bash
./tools/publish.sh push-gitee --authorize-gitee
./tools/publish.sh push-gitee --authorize-gitee --execute --confirm-mirror
```

Gitee 只同步源码 `main`，不发布 tag、Release 或二进制。

签名发布构建：

```bash
./tools/release.sh check
./tools/release.sh build
```

GitHub Release（仅在所有者明确授权后）：

```bash
./tools/release.sh publish-github --authorize-github
./tools/release.sh publish-github --authorize-github --execute
```

- 不要用 `gh release create`、手写 `git tag`、`git push --tags` 或其他旁路命令替代这些脚本。
- GitHub Release 前必须先完成 GitHub 源码发布，并确认 `github/main == HEAD`；tag 由 `release.sh` 创建和推送。
- `GH_TOKEN` 必须属于仓库所有者 `pchartming`；不要使用其他个人 `gh` 会话发布。
- 任何脚本 dry-run 通过后，仍要检查输出再执行；不要跳过 `--authorize-github` / `--authorize-gitee` 的授权边界。

## 构建与部署规则

- 不在本机运行 Gradle。构建统一走 `tools/build_remote.sh` 或 `tools/release.sh` 的远端构建机。
- `.env.build` 只保存在本机，不提交、不打印、不复制到构建产物。
- 构建机地址、用户名、SSH 密钥路径只放在本机 `.env.build`；不得写入仓库、日志、错误信息或提交内容。
- 不使用 `sudo`，不在远端执行 Git 操作，不复用其他项目的构建目录。只使用本项目 `.env.build` 配置的专用目录。
- 只从干净的 `main` 构建；`versionCode` 必须递增；版本说明必须位于 `docs/releases/v<versionName>.md`。
- debug APK 永远不是发布产物；未签名 APK 不得上传或分发。
- Release APK 必须验证包名、版本、签名证书、zipalign、非 debuggable、单签名者和无 `INTERNET` 权限。
- 发布产物写入 `dist/release/<tag>/`，不进入 Git。不得把 APK、AAB、`.idsig`、`dist/` 或签名文件提交到仓库。

## 密钥与敏感信息

- `.env.release`、`.env.build`、`.publish-denylist.txt`、keystore、密码、`GH_TOKEN`、APK/AAB/`.idsig` 不得进入 `main` 或任何公开远端。
- 本机工作密钥为 `$HOME/keys/tiku-prompt-release.jks`，`.env.release` 权限必须为 `600`。
- 私仓中存在仅用于发布密钥备份的私有分支。它是凭据存储，不是源码分支：
  - 永不合并或 cherry-pick 到 `main`；
  - 永不推送到 GitHub/Gitee，永不镜像或导入公开仓库；
  - 不要把它包含进源码包或公开文档；
  - 不要用 `--no-verify` 绕过本地 `pre-push` 保护。
- 不要 `cat`、打印或截图密钥和 `.env.release`；不要把密码放在命令行参数、日志或提交信息中。
- 公开源码必须通过 `./tools/publish.sh check` 和 gitleaks。检查失败时停止修复，不要跳过或弱化检查。

## 绝对禁止

- 直接 `git push` 到 GitHub/Gitee，或未经授权添加/修改这两个远端。
- 本地 Gradle 构建、`sudo`、在构建机执行 Git、修改其他项目目录。
- 把 debug APK、未签名 APK、缺少版本说明的构建当作发布。
- 先创建 GitHub Release 再补源码发布。
- 把私钥、密码、token、keystore 或密钥备份分支合并进公开源码。
- 用 `--no-verify`、删除 tag、force push 或改写历史来“修复”发布问题。

## 提交前检查

1. `git branch --show-current` 必须是 `main`，`git status --short` 必须为空。
2. 运行 `./tools/publish.sh check --worktree`，确认 0 error、gitleaks 通过。
3. 推私仓先 dry-run，再 `push-private --execute`。
4. GitHub/Gitee 只有在当前任务有明确授权时才执行，并先查看 dry-run。
5. 发布 APK 先 `./tools/release.sh check && ./tools/release.sh build`，再核对 `SHA256SUMS` 和 `release-info.txt`。
6. 任何检查或钩子失败都停下来修复；不要绕过。