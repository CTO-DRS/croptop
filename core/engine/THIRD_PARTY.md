# Native third-party notices

This inventory covers the native dependencies configured in
`src/main/cpp/cmake/Dependencies.cmake`. Release archives are checksum-pinned
there. The listed libraries are statically linked into `libcroptop.so` except
for Android's platform zlib, which libpng/libtiff may use through the NDK.
Keep the license texts and copyright notices available in the distributed
application (including the Licenses screen) and source distributions.

| Component | Version | License | Link mode | Notices and obligations |
|---|---:|---|---|---|
| LibRaw | 0.22.2 | CDDL-1.0 (CropTop selects the CDDL option of LGPL-2.1 OR CDDL-1.0) | Static (`cmake/LibRaw.cmake`) | Include the CDDL text and LibRaw's copyright notices; modified LibRaw source files, if any, must be made available. |
| Clipper2 | 1.5.4 | Boost Software License 1.0 | Static (`cmake/Clipper2.cmake`) | Include the license text in source distributions; binary-only redistribution needs no notice under BSL-1.0, and the text ships with the app's licenses anyway. |
| libaom | 3.13.1 | BSD-2-Clause + AOMedia patent license (`PATENTS`) | Static (`cmake/Avif.cmake`) | Ship `licenses/libaom-BSD-2-Clause.txt` and `libaom-PATENTS.txt`. Built without assembly, `-Os`; encode and decode. |
| libavif (with its bundled libyuv subset) | 1.3.0 | BSD-2-Clause (libyuv: BSD-3-Clause) | Static (`cmake/Avif.cmake`) | Ship `licenses/libavif-LICENSE.txt`, which carries the third-party notices. |
| libultrahdr (with image_io, modp_b64) | 1.4.0 | Apache-2.0 (modp_b64: BSD-2-Clause) | Static (`cmake/UltraHdr.cmake`) | Ship `licenses/libultrahdr-Apache-2.0.txt`, `modp_b64-BSD.txt`. Gain-map technology is licensed by Adobe: the app must show "This product includes Gain Map technology under license by Adobe." (`licenses/libultrahdr-adobe-gain-map-NOTICE.txt`) in its licenses screen. |
| LiteRT + LiteRT GPU delegate | 1.4.2 | Apache-2.0 | Maven AAR (`core/ai`, `libs.versions.toml`) | Bundled `libtensorflowlite_jni.so` and `libtensorflowlite_gpu_jni.so` are 16 KB aligned (checked with `check_elf_alignment.sh`). Adds about 2-3 MB per ABI to the APK; ship AI in a feature module if the budget bites. |
| Zstandard (`zstd`) | 1.5.7 | BSD-3-Clause OR GPL-2.0-or-later; CropTop selects BSD-3-Clause | Static | Include its copyright, license conditions, and disclaimer. Do not use contributor names to endorse the product. |
| Little CMS (`lcms2`) | 2.19 | MIT | Static | Include copyright, permission notice, and disclaimer. |
| libjpeg-turbo | 3.2.0 | IJG License, Modified BSD-3-Clause, and zlib license (component files carry their applicable notices) | Static | Include all upstream license and copyright notices; retain attribution and disclaimers. |
| libpng | 1.6.58 | PNG Reference Library License | Static | Preserve the copyright and license notice; mark modified source versions clearly. |
| libwebp | 1.6.0 | BSD-3-Clause, with an additional patent grant | Static | Include BSD notice and disclaimer; review the upstream patent grant when redistributing. |
| libtiff | 4.7.2 | libtiff License (permissive, BSD-like) | Static | Include copyright, permission notice, and disclaimer; do not use named contributors to advertise without permission. |
| LLVM libc++ (`libc++_shared.so`) | NDK r27.1 (LLVM 18) | Apache-2.0 with LLVM Exceptions | Dynamic, bundled in the APK per ABI | Include the license text; the LLVM exception covers compiled-form redistribution. |
| lensfun lens database | snapshot bbd4332 (2026-09-24) | CC BY-SA 3.0 (data only; no lensfun code is used) | Data file shipped with the app (`tools/fetch-lensfun-db.sh`) | Attribute the lensfun project; ShareAlike applies to the database files and any adaptation of them, not to CropTop code. |
| Android zlib | NDK platform | Android platform component; supplied by the Android system | Dynamic platform library | Not bundled from this dependency configuration. Recheck if zlib is ever bundled instead. |
| GoogleTest (development only) | 1.17.0 | BSD-3-Clause | Host test executable only | Retain its upstream license if redistributing the test harness; not linked into the app. |
| Google Benchmark (development only) | 1.9.5 | Apache-2.0 | Host benchmark executable only | Retain its upstream license if redistributing the benchmark harness; not linked into the app. |

Full license texts for every shipped component (pinned upstream versions, plus the Inter and JetBrains Mono fonts) are checked in under
[`licenses/`](licenses/). The application must bundle that directory next to this file (assets `licenses/`).

Upstream sources and license files:

- [zstd 1.5.7](https://github.com/facebook/zstd/tree/v1.5.7) — `LICENSE` and `COPYING`
- [Little CMS 2.19](https://github.com/mm2/Little-CMS/tree/lcms2.19) — `LICENSE`
- [libjpeg-turbo 3.2.0](https://github.com/libjpeg-turbo/libjpeg-turbo/tree/3.2.0) — `LICENSE.md`, `README.ijg`, and `turbojpeg.c` license notices
- [libpng 1.6.58](https://github.com/pnggroup/libpng/tree/v1.6.58) — `LICENSE`
- [libwebp 1.6.0](https://chromium.googlesource.com/webm/libwebp/+/refs/tags/v1.6.0/) — `COPYING` and `PATENTS`
- [LibRaw 0.22.2](https://github.com/LibRaw/LibRaw/tree/0.22.2) — `LICENSE.CDDL`, `COPYRIGHT`
- [lensfun database](https://github.com/lensfun/lensfun/tree/bbd4332a9ec566fd9aa548c9e0d8ced238c56261/data) — `COPYING.CC_BY-SA_3.0`
- [Clipper2 1.5.4](https://github.com/AngusJohnson/Clipper2/tree/Clipper2_1.5.4) — `LICENSE`
- [libaom 3.13.1](https://aomedia.googlesource.com/aom/) — `LICENSE`, `PATENTS`
- [libavif 1.3.0](https://github.com/AOMediaCodec/libavif/tree/v1.3.0) — `LICENSE`
- [libultrahdr 1.4.0](https://github.com/google/libultrahdr/tree/v1.4.0) — `LICENSE`, `adobe-hdr-gain-map-license/NOTICE`
- [libtiff 4.7.2](https://gitlab.com/libtiff/libtiff/-/tree/v4.7.2) — `LICENSE.md`
- [GoogleTest 1.17.0](https://github.com/google/googletest/tree/v1.17.0) — development-only dependency, `LICENSE`
- [Google Benchmark 1.9.5](https://github.com/google/benchmark/tree/v1.9.5) — development-only dependency, `LICENSE`

## Distribution checklist

- Ship the exact upstream notices for the selected versions in the app's
  Licenses screen or a bundled notices document.
- Keep these notices when redistributing the application or source.
- Re-run this inventory whenever versions, build options, or link modes change.
- Confirm the built dependency graph and final packaged artifacts before a
  release; this inventory describes the configured build, not a legal opinion.
