# 题库提示词（Tiku Prompt）

安卓端的离线 AI 出题提示词生成器。选择目标 App 的 JSON 格式和出题范围后，在本机拼好提示词，复制或分享到任意 AI 对话；模型返回 JSON 后，再导入目标 App。

应用本身**不调用模型、不需要 API Key、不联网**，`AndroidManifest.xml` 中没有 `INTERNET` 权限。提示词、补充资料和表单设置只在本机处理。

> 本项目提供非官方的导入格式兼容；目标 App 更新后，导入行为可能变化。

## 支持的目标格式

| 目标 | 导入格式 | 题型 |
| --- | --- | --- |
| StudyOnTV / 题了个题 | v2 `{version: 2, items}` | 单选、多选、判断、填空、主观 |
| PlanYourTV | v1 `{version, items}` | 单选、多选 |
| 学习锁 | v2.5+ `{items}`，每题 `s/o/a/e` | 四选一单选题（固定 4 个选项） |

出题范围（年级、学期、学科、教材、单元/课时、题量、难度、输出语言、额外要求、补充资料）由所有格式共享；切换目标只替换提示词末尾的 JSON 格式规范，已选范围会保留。

## 使用

1. 选择目标 App、教材和出题范围。
2. 按需设置题型、题量、难度、选项数、输出语言。
3. 复制提示词，粘贴到任意 AI 对话。
4. 把 AI 返回的 JSON 导入目标 App，并人工核对题目、答案和解析。
5. 学习锁需要先把 JSON 保存为 UTF-8 无 BOM 的 `.json`，再直接压缩为 zip 导入；应用内“格式说明”列出了具体步骤。

补充资料可以粘贴教材原文、公式、单词表或老师强调的知识点。内置课程目录只有教材、单元和课时标题，没有课文正文；需要严格贴合课本时，请自行补充资料。

## 数据与隐私

- 课程目录包含 410 本教材、2870 个单元和 8973 条非空课时标题，不含教材正文、图片、题目或答案。
- 目录数据取自 PlanYourTV 的教材目录资产（MIT），其标题整理自国家中小学智慧教育平台公开的课程目录。
- 目标 App 的格式规范用于描述导入 JSON 的形状和校验规则。
- StudyOnTV 的格式规范依据公开文档整理；其自身许可证为 Source Available / 个人使用。
- 学习锁是免费但闭源的第三方软件；本项目只描述其公开可见的导入格式。
- 应用不收集、不上传任何数据，也没有网络权限。剪贴板和系统分享由用户主动触发。
- AI 输出的准确性由模型和使用者负责；导入前请核对，尤其是多选题、填空题和解析内容。

更完整的数据来源与第三方许可证说明见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) 和 [NOTICE](NOTICE)。

## 构建与测试

本地构建要求：

- JDK 17
- Android SDK Platform 36
- Android Build-Tools 35.0.0
- Gradle Wrapper（仓库已包含）

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

单元测试是纯 JVM 测试，会校验课程数据、目标顺序、格式切换、提示词拼接和错误处理。生成的 debug APK 位于 `app/build/outputs/apk/debug/`。

## 代码结构

```text
app/src/main/
  assets/quiz/          年级、学科、教材、单元与课时目录（只读数据）
  assets/prompts/       各目标格式的格式规范
  java/.../model/       表单与领域模型
  java/.../data/        资产解析、课程目录、表单持久化
  java/.../prompt/      目标 Profile 元数据、表单校正、提示词拼接
  java/.../ui/          Compose 界面与 ViewModel
app/src/test/           纯 JVM 单元测试
```

添加新的答题 App 格式时，通常只需要：

1. 在 `app/src/main/assets/prompts/` 增加格式规范，末尾保留“出题要求：”标题；
2. 在 `TargetProfiles.kt` 注册格式元数据、支持的题型和数量/选项边界；
3. 补充对应测试，确认切换目标时共享范围不会丢失。

## 许可证

本项目代码以 [MIT License](LICENSE) 发布。课程目录、第三方依赖和第三方 App 名称仍受各自的条款约束，详见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)。
