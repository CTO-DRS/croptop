# `.croptop` reader fuzzing

Run the standalone format archive harness with Clang/libFuzzer and AddressSanitizer:

```sh
CROPTOP_FORMAT_FUZZ_SECONDS=60 core/engine/src/main/cpp/run_croptop_format_fuzzer.sh
```

The runner configures the checked-in native CMake project and target in an out-of-tree build at
`/tmp/croptop-format-fuzzer-co3` by default, separate from the decoder fuzzer's build and corpus. Set
`CROPTOP_FORMAT_FUZZ_BUILD_DIR` to choose another build/corpus directory. It extracts the committed
frozen v1 `.croptop` fixture into that corpus, then libFuzzer keeps any coverage-increasing inputs
there and writes crash artifacts under its `artifacts/` directory.

The harness exercises `CroptopArchive::open`, manifest/ICC/preview reads, UUID enumeration, layer
metadata and bounded base-layer reads. It accepts at most 1 MiB per fuzz input and configures archive
limits of 64 entries, a 512 KiB central directory, a 64 KiB manifest, 16 KiB ICC, 64 KiB previews,
256-pixel layer dimensions, and at most eight layer loads per input. Each layer load uses one worker.

The native target is `croptop_format_fuzzer`, enabled with `CROPTOP_BUILD_FUZZERS=ON`. The decoder
fuzzer has its own runner and corpus directory; this script does not overwrite them.
