# JVM and Android application dependencies

The Apache License, Version 2.0 text is in `apache-2.0.txt` in this directory. This notice groups direct application dependencies and their catalog versions; transitive AndroidX/Compose artifacts use the versions selected by the AndroidX Compose BOM or Gradle dependency resolution.

| Component | Version / resolution | License | Upstream |
|---|---|---|---|
| AndroidX Core KTX | 1.19.1 | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support/ |
| AndroidX Activity Compose | 1.13.0 | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support/ |
| AndroidX Lifecycle | 2.11.0 | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support/ |
| AndroidX Navigation 3 | 1.2.0 | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support/ |
| AndroidX Compose | BOM 2026.09.00; component versions BOM-managed | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support/ |
| AndroidX Room | 2.8.5 | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support/ |
| AndroidX Paging | 3.5.1 | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support/ |
| AndroidX DataStore | 1.2.1 | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support/ |
| AndroidX DataStore External Protobuf | 1.2.1 | BSD-3-Clause | https://android.googlesource.com/platform/frameworks/support/ |
| AndroidX Window | 1.5.1 | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support/ |
| AndroidX Graphics Core | 1.0.4 | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support/ |
| AndroidX Input Motion Prediction | 1.0.0 | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support/ |
| AndroidX Profile Installer | 1.4.1 | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support/ |
| Kotlin | 2.4.20 | Apache-2.0 | https://github.com/JetBrains/kotlin |
| kotlinx.coroutines | 1.11.0 | Apache-2.0 | https://github.com/Kotlin/kotlinx.coroutines |
| kotlinx.serialization | 1.11.0 | Apache-2.0 | https://github.com/Kotlin/kotlinx.serialization |
| Dagger / Hilt | 2.60.1 | Apache-2.0 | https://github.com/google/dagger |
| Inter font | 4.x (bundled font asset) | SIL Open Font License 1.1 | See `inter-OFL-1.1.txt` |
| JetBrains Mono font | 2.x (bundled font asset) | SIL Open Font License 1.1 | See `jetbrains-mono-OFL-1.1.txt` |

The AndroidX and JetBrains upstream repositories contain component-level copyright notices. This direct-dependency notice does not yet enumerate every transitive artifact in the resolved Release dependency graph.
