# Per-format libFuzzer targets. Included from the CROPTOP_BUILD_FUZZERS block of the
# parent CMakeLists, after croptop_engine_core has been built with -fsanitize=fuzzer-no-link,address.
# Each target is croptop_fuzz_<name>; fuzz/run_fuzzers.sh builds and runs them with their seed corpora.
set(CROPTOP_FORMAT_FUZZERS tiff psd codec_extra abr lut xmp icc font metadata shape_record raw avif ultrahdr)
foreach(name IN LISTS CROPTOP_FORMAT_FUZZERS)
    add_executable(croptop_fuzz_${name} fuzz_${name}.cpp)
    target_compile_options(croptop_fuzz_${name} PRIVATE
        -Wall -Wextra -Wpedantic -Werror -fsanitize=fuzzer-no-link,address,undefined -fno-sanitize=vptr
        -fno-sanitize-recover=undefined -fno-omit-frame-pointer)
    target_link_options(croptop_fuzz_${name} PRIVATE -fsanitize=fuzzer,address,undefined -fno-sanitize=vptr)
    target_link_libraries(croptop_fuzz_${name} PRIVATE croptop_engine_core)
endforeach()
