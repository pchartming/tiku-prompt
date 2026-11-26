# Third-party notices

Tiku Prompt's original source code is licensed under the [MIT License](LICENSE).
The items below keep their own terms. The MIT License does not grant rights to
third-party data, app names, trademarks, or dependencies.

## PlanYourTV course catalog

- Files: `app/src/main/assets/quiz/curriculum.json`, `app/src/main/assets/quiz/unit_catalog.json`
- Source project: PlanYourTV
- Copyright: 2026 pengchujin; 2026 PlanYourTV contributors
- License: MIT

The bundled catalog is a title-only subset derived from PlanYourTV's textbook
catalog asset. It contains book, unit, and nonblank lesson titles; it contains no
textbook body text, images, question banks, answers, or other third-party media.
The catalog's `source` metadata points to the National Smart Education Platform
public curriculum catalog.

The PlanYourTV catalog is distributed under the following license:

```text
MIT License

Copyright (c) 2026 pengchujin
Copyright (c) 2026 PlanYourTV contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

PlanYourTV's source code is also MIT-licensed. Any reused code must retain the
copyright and permission notice above.

## National Smart Education Platform

The course titles in the catalog were compiled from the public curriculum
catalog at <https://basic.smartedu.cn/>. Textbook names, unit names, and lesson
titles may be subject to their respective rights. No textbook content is bundled.

## StudyOnTV / 题了个题

StudyOnTV / 题了个题 is a third-party application with its own license terms.
The import-format description in this repository is based on public
documentation. Compatibility is unofficial and may change when StudyOnTV
changes.

## 学习锁

学习锁 is a free, closed-source third-party application. The format description
and prompt document a publicly observable JSON import shape. Compatibility is
unofficial and may change without notice.

## HaloTV

HaloTV is a third-party open-source application. The import-format description
in this repository is based on its publicly available parent-quiz format. No
HaloTV source code, assets, or question banks are bundled. Compatibility is
unofficial and may change when HaloTV changes.

## Build and runtime dependencies

These libraries are resolved by Gradle and are not vendored source in this
repository. Their license texts ship with the resolved artifacts.

| Component | License |
| --- | --- |
| AndroidX / Jetpack Compose / Material 3 | Apache License 2.0 |
| Kotlin standard library and Kotlin Gradle plugins | Apache License 2.0 |
| kotlinx.coroutines | Apache License 2.0 |
| kotlinx.serialization | Apache License 2.0 |
| Android Gradle Plugin | Apache License 2.0 |
| Gradle wrapper | Apache License 2.0 |
| JUnit 4 (tests only) | Eclipse Public License 1.0 |

The Android SDK and build tools are not redistributed by this repository.

## Trademarks

Android, Google Play, Kotlin, Jetpack, and related marks are trademarks of their
respective owners. PlanYourTV, StudyOnTV / 题了个题, 学习锁, and HaloTV are
third-party project or product names used descriptively to identify compatible
formats and data provenance.
