# Dependency and redistribution record

The application code is `GPL-2.0-or-later`. That expression is required because Apache-2.0 components are compatible with GPLv3 but not strict GPLv2-only. The root retains the requested unmodified GPLv2 license text, and distribution notices preserve every third-party license.

| Component | Pinned version | Shipped in APK | License | Rationale |
|---|---:|---:|---|---|
| Android platform APIs | API 37.0 | platform-provided | Apache-2.0/platform terms | Camera2, MediaCodec, Keystore, UI, service, and lifecycle APIs; no support UI framework is needed. |
| Kotlin standard library | 2.2.10 (resolved release runtime graph) | yes | Apache-2.0 | Required by the mandated Kotlin application layer; compatible through GPL-3.0-or-later. |
| JetBrains annotations | 13.0 (transitive from Kotlin stdlib) | yes | Apache-2.0 | JVM annotation metadata; no runtime service or network behavior. |
| ZXing core | 3.5.4 | yes | Apache-2.0 | Offline QR decode only; no Play Services, network, analytics, or dynamic model. |
| SRT | 1.5.6 | yes, native | MPL-2.0 with secondary-license compatibility | Maintained native SRT caller; selected release includes bounds/security fixes. Modified SRT files remain MPL-2.0. |
| Botan | 3.12.0 | yes, native | BSD-2-Clause | SRT's documented Android-capable crypto backend selected for preview AES-GCM AEAD and a narrow redistribution boundary. |
| AndroidX Test / Espresso | 1.7.0 / 3.7.0 | test APK only | Apache-2.0 | Instrumentation only; absent from production APK. |
| JUnit | 4.13.2 | host tests only | EPL-1.0 | Test tool only; absent from production APK. |

Build tools (AGP, Gradle, JDK, SDK, NDK, CMake) are not linked into or redistributed inside the APK. Their versions are pinned for reproducibility.

Gradle dependency verification is locked with SHA-256 checksums in `mobile/gradle/verification-metadata.xml`. The verified release runtime graph contains only Kotlin stdlib 2.2.10, JetBrains annotations 13.0, and ZXing core 3.5.4. The production APK also embeds `assets/THIRD_PARTY_NOTICES.txt`; `LICENSES/Apache-2.0.txt` supplies the full terms for the JVM runtime components, while the vendored SRT and Botan trees retain their full MPL-2.0 and BSD-2-Clause license files and modified MPL build sources.

The final release dependency graph and APK contents were inspected: no analytics SDK, cloud client, AndroidX runtime, or undeclared native object is present.
