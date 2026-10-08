Deterministic generated Bayer RAW fixture for NativeRawOpenTest.

Source: croptop::testing::syntheticDng() in
core/engine/src/main/cpp/native_tests_synthetic_dng.h
Regenerate: core/engine/tools/generate-synthetic-dng-fixture.sh
SHA-256: 8aa9fc1576c8a541d87179c56bfeee8f0dc0e157876a7770c030891efea9fe1e
Format: 64x48, uncompressed 16-bit RGGB DNG; synthetic neutral gray scene.

This fixture proves deterministic app RAW-open plumbing and the Bayer/DNG decode path. It is not a real-camera
compatibility sample and does not validate embedded previews, vendor-specific compression, or camera metadata.
