# Native build contract for Android ARM64 and x86_64.
cmake_minimum_required(VERSION 3.22.1)
project(CropTop VERSION 0.1.0 LANGUAGES CXX)
if(POLICY CMP0135)
    cmake_policy(SET CMP0135 NEW)
endif()

option(CROPTOP_ENABLE_SANITIZERS "Enable address and undefined behavior sanitizers" ON)
option(CROPTOP_ENABLE_TSAN "Enable ThreadSanitizer for host-native checks" OFF)
option(CROPTOP_ENABLE_LTO "Enable interprocedural optimization when supported" ON)
option(CROPTOP_FETCH_NATIVE_DEPS "Build pinned codec and color dependencies from source" ON)
option(CROPTOP_BUILD_TESTS "Build host-native GoogleTest checks" OFF)
option(CROPTOP_BUILD_BENCHMARKS "Build Google Benchmark targets" OFF)
option(CROPTOP_BUILD_FUZZERS "Build libFuzzer targets for native decoders and .croptop archives" OFF)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "Link native dependencies statically" FORCE)

if(CROPTOP_FETCH_NATIVE_DEPS)
    include(cmake/Dependencies.cmake)
    include(cmake/LibRaw.cmake)
    include(cmake/Clipper2.cmake)
    include(cmake/Avif.cmake)
    include(cmake/UltraHdr.cmake)
    if(NOT ANDROID)
        find_package(ZLIB REQUIRED)
    endif()
endif()

if(CROPTOP_FETCH_NATIVE_DEPS)
    add_library(croptop_image_metrics STATIC image_metrics.cpp)
    target_include_directories(croptop_image_metrics PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}")
    target_compile_options(croptop_image_metrics PRIVATE -Wall -Wextra -Wpedantic -Werror)
endif()

set(CROPTOP_ENGINE_CORE_SOURCES tile_store.cpp object_select.cpp scratch_store.cpp blend_modes.cpp render_graph.cpp job_system.cpp gif_decoder.cpp selection_mask.cpp selection_modify.cpp selection_color.cpp selection_transform.cpp selection_quick.cpp selection_magnetic.cpp resample.cpp layer_pixel_ops.cpp transform_session.cpp crop_geometry.cpp refine_edge.cpp mask_upsample.cpp avif_codec.cpp ultra_hdr.cpp gradient_fill.cpp stroke_stabilizer.cpp content_aware_fill.cpp heal.cpp retouch.cpp red_eye.cpp liquify.cpp mesh_warp.cpp abr_import.cpp fill_command.cpp content_aware_sampling.cpp lut.cpp filters_blur.cpp filters_noise.cpp filters_distort.cpp filter_dispatch.cpp defringe.cpp lens_correction.cpp lens_profile.cpp lens_blur.cpp histogram.cpp image_metadata.cpp upright.cpp develop_upright_bridge.cpp develop_xmp.cpp adjustments.cpp layer_styles.cpp layer_styles_codec.cpp psd_descriptor.cpp vector_path.cpp shape_layer.cpp jni_shape_layer.cpp layer_mask.cpp layer_mask_ops.cpp develop_recipe_bridge.cpp shape_render_adapter.cpp develop_masks.cpp develop_mask_ops.cpp develop_mask_blend.cpp develop_batch.cpp adjustment_params.cpp brush_engine.cpp stroke_compositor.cpp adjustment_lut_bridge.cpp adjustment_preview_scheduler.cpp color_management.cpp develop_tone.cpp develop_color.cpp develop_detail.cpp develop_pipeline.cpp develop_settings_codec.cpp cff_outlines.cpp opentype_gsub.cpp opentype_kerning.cpp opentype_marks.cpp text_bidi.cpp text_effects.cpp text_layout.cpp truetype_font.cpp font_variations.cpp smart_object.cpp stack_merge.cpp puppet_warp.cpp panorama.cpp stroke_symmetry.cpp gpu_quirks.cpp raw_demosaic.cpp raw_amaze.cpp raw_xtrans.cpp)
if(CROPTOP_FETCH_NATIVE_DEPS)
    list(APPEND CROPTOP_ENGINE_CORE_SOURCES image_decoder.cpp webp_codec.cpp croptop_format.cpp recovery_journal.cpp recovery_autosave.cpp psd_reader.cpp psd_import.cpp psd_writer.cpp psd_effects_writer.cpp vector_boolean.cpp curve_fit.cpp raw_decode.cpp raw_open.cpp raw_lens.cpp raw_smart_object.cpp tiff_codec.cpp psd_layer_data.cpp psd_compose.cpp codec_extra.cpp)
endif()
add_library(croptop_engine_core STATIC ${CROPTOP_ENGINE_CORE_SOURCES})
target_include_directories(croptop_engine_core PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}")
target_compile_options(croptop_engine_core PRIVATE -Wall -Wextra -Wpedantic -Werror)
include(cmake/GpuDevelop.cmake)
if(CROPTOP_FETCH_NATIVE_DEPS)
    target_link_libraries(croptop_engine_core PUBLIC libzstd_static jpeg-static png_static webp libwebpmux lcms2 croptop_libraw croptop_clipper2 croptop_avif_lib croptop_uhdr)
    if(ANDROID)
        target_link_libraries(croptop_engine_core PUBLIC z)
    else()
        target_link_libraries(croptop_engine_core PUBLIC ZLIB::ZLIB)
    endif()
endif()

if(CROPTOP_ENABLE_TSAN)
    if(ANDROID)
        message(FATAL_ERROR "CROPTOP_ENABLE_TSAN is for host-native checks only")
    endif()
    if(CROPTOP_ENABLE_SANITIZERS)
        message(FATAL_ERROR "ThreadSanitizer cannot be combined with ASan/UBSan")
    endif()
    target_compile_options(croptop_engine_core PUBLIC -fsanitize=thread -fno-omit-frame-pointer)
    target_link_options(croptop_engine_core INTERFACE -fsanitize=thread)
endif()

if(ANDROID)
    if(CMAKE_HOST_SYSTEM_NAME STREQUAL "Linux")
        set(CROPTOP_GLSLC_HINT "${CMAKE_ANDROID_NDK}/shader-tools/linux-x86_64")
    elseif(CMAKE_HOST_SYSTEM_NAME STREQUAL "Darwin")
        set(CROPTOP_GLSLC_HINT "${CMAKE_ANDROID_NDK}/shader-tools/darwin-x86_64")
    elseif(CMAKE_HOST_SYSTEM_NAME STREQUAL "Windows")
        set(CROPTOP_GLSLC_HINT "${CMAKE_ANDROID_NDK}/shader-tools/windows-x86_64")
    endif()
    find_program(CROPTOP_GLSLC NAMES glslc HINTS "${CROPTOP_GLSLC_HINT}" REQUIRED)
    find_program(CROPTOP_SPIRV_VAL NAMES spirv-val HINTS "${CROPTOP_GLSLC_HINT}" REQUIRED)
    set(CROPTOP_BLEND_SPIRV "${CMAKE_CURRENT_BINARY_DIR}/generated/blend.comp.spv")
    set(CROPTOP_BLEND_HEADER "${CMAKE_CURRENT_BINARY_DIR}/generated/blend_comp_spv.h")
    add_custom_command(OUTPUT "${CROPTOP_BLEND_SPIRV}"
        COMMAND "${CMAKE_COMMAND}" -E make_directory "${CMAKE_CURRENT_BINARY_DIR}/generated"
        COMMAND "${CROPTOP_GLSLC}" --target-env=vulkan1.1 -Werror
            -o "${CROPTOP_BLEND_SPIRV}" "${CMAKE_CURRENT_SOURCE_DIR}/shaders/blend.comp"
        COMMAND "${CROPTOP_SPIRV_VAL}" --target-env vulkan1.1 "${CROPTOP_BLEND_SPIRV}"
        DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/shaders/blend.comp"
        VERBATIM
    )
    add_custom_command(OUTPUT "${CROPTOP_BLEND_HEADER}"
        COMMAND "${CMAKE_COMMAND}" "-DINPUT=${CROPTOP_BLEND_SPIRV}" "-DOUTPUT=${CROPTOP_BLEND_HEADER}"
            -P "${CMAKE_CURRENT_SOURCE_DIR}/cmake/embed_spirv.cmake"
        DEPENDS "${CROPTOP_BLEND_SPIRV}" "${CMAKE_CURRENT_SOURCE_DIR}/cmake/embed_spirv.cmake"
        VERBATIM
    )
    add_custom_target(croptop_blend_shader DEPENDS "${CROPTOP_BLEND_HEADER}")
    add_library(croptop SHARED jni_entry.cpp jni_selection.cpp jni_selection_budget.cpp jni_adjustments.cpp jni_brush.cpp jni_adjustment_lut.cpp jni_shape_layer.cpp jni_shape_render.cpp jni_geometry.cpp jni_layer_pixel_ops.cpp jni_flatten.cpp jni_adjustment_layer.cpp jni_object_select.cpp jni_pixels.cpp jni_magnetic_lasso.cpp jni_mask_overlay.cpp jni_text_layer.cpp jni_psd_import.cpp jni_recovery.cpp layer_style_attachment.cpp jni_layer_style_attach.cpp jni_croptop_archive.cpp jni_codec_extra.cpp jni_raw.cpp jni_layer_mask.cpp jni_smart_object.cpp jni_lens_profile.cpp jni_defringe.cpp jni_mesh_warp.cpp jni_avif_uhdr.cpp jni_develop.cpp jni_develop_masks.cpp jni_filters.cpp jni_image_ops.cpp jni_color_management.cpp jni_layer_styles.cpp jni_upright.cpp jni_stack_pano.cpp jni_puppet_warp.cpp jni_path_ops.cpp jni_transform_session.cpp jni_lens_blur.cpp jni_fill.cpp jni_gradient.cpp vulkan_canvas.cpp vulkan_blend_pipeline.cpp)
    add_dependencies(croptop croptop_blend_shader)
    target_include_directories(croptop PRIVATE "${CMAKE_CURRENT_BINARY_DIR}/generated")
    target_compile_definitions(croptop PRIVATE
        CROPTOP_VERSION="${PROJECT_VERSION}"
        CROPTOP_BUILD_TYPE="$<IF:$<CONFIG:Debug>,debug,release>"
    )
    target_compile_options(croptop PRIVATE
        -Wall -Wextra -Wpedantic -Werror
        -fvisibility=hidden
        $<$<CONFIG:Release>:-O3>
    )
    target_compile_definitions(croptop PRIVATE VK_USE_PLATFORM_ANDROID_KHR CROPTOP_HAS_VULKAN=1)
    target_link_libraries(croptop PRIVATE croptop_engine_core android jnigraphics log vulkan)
endif()

if(CROPTOP_ENABLE_SANITIZERS AND CMAKE_BUILD_TYPE STREQUAL "Debug")
    if(CROPTOP_FETCH_NATIVE_DEPS)
        target_compile_options(croptop_image_metrics PRIVATE -fsanitize=address,undefined -fno-omit-frame-pointer)
        target_link_options(croptop_image_metrics INTERFACE -fsanitize=address,undefined)
    endif()
    target_compile_options(croptop_engine_core PRIVATE -fsanitize=address,undefined -fno-omit-frame-pointer)
    target_link_options(croptop_engine_core INTERFACE -fsanitize=address,undefined)
    if(ANDROID)
        target_compile_options(croptop PRIVATE -fsanitize=address,undefined -fno-omit-frame-pointer)
        target_link_options(croptop PRIVATE -fsanitize=address,undefined)
    endif()
endif()

if(CROPTOP_ENABLE_LTO)
    include(CheckIPOSupported)
    check_ipo_supported(RESULT CROPTOP_IPO_SUPPORTED OUTPUT CROPTOP_IPO_ERROR)
    if(CROPTOP_IPO_SUPPORTED AND TARGET croptop)
        set_property(TARGET croptop PROPERTY INTERPROCEDURAL_OPTIMIZATION_RELEASE TRUE)
    else()
        message(STATUS "LTO unavailable for this toolchain: ${CROPTOP_IPO_ERROR}")
    endif()
endif()

# Ensure 16 KB Android devices can load the native library (Android 15+).
if(ANDROID)
    target_link_options(croptop PRIVATE "-Wl,-z,max-page-size=16384")
endif()
if(CROPTOP_FETCH_NATIVE_DEPS)
    target_link_libraries(croptop_image_metrics PUBLIC png_static)
    if(ANDROID)
        target_link_libraries(croptop PRIVATE
        croptop_image_metrics
        lcms2
        jpeg-static
        png_static
        tiff
        )
        target_compile_definitions(croptop PRIVATE
        CROPTOP_ZSTD_VERSION="1.5.7"
        CROPTOP_LCMS_VERSION="2.19"
        CROPTOP_JPEG_TURBO_VERSION="3.2.0"
        CROPTOP_PNG_VERSION="1.6.58"
        CROPTOP_WEBP_VERSION="1.6.0"
        CROPTOP_TIFF_VERSION="4.7.2"
        )
    endif()
else()
    if(ANDROID)
        target_compile_definitions(croptop PRIVATE
            CROPTOP_ZSTD_VERSION="disabled"
            CROPTOP_LCMS_VERSION="disabled"
            CROPTOP_JPEG_TURBO_VERSION="disabled"
            CROPTOP_PNG_VERSION="disabled"
            CROPTOP_WEBP_VERSION="disabled"
            CROPTOP_TIFF_VERSION="disabled"
        )
    endif()
endif()

if(CROPTOP_BUILD_TESTS)
    if(NOT CROPTOP_FETCH_NATIVE_DEPS)
        message(FATAL_ERROR "CROPTOP_BUILD_TESTS requires CROPTOP_FETCH_NATIVE_DEPS for PNG golden-image IO")
    endif()
    include(CTest)
    set(BUILD_GMOCK OFF CACHE BOOL "" FORCE)
    set(INSTALL_GTEST OFF CACHE BOOL "" FORCE)
    FetchContent_Declare(googletest
        URL https://github.com/google/googletest/releases/download/v1.17.0/googletest-1.17.0.tar.gz
        URL_HASH SHA256=65fab701d9829d38cb77c14acdc431d2108bfdbf8979e40eb8ae567edf10b27c
    )
    FetchContent_MakeAvailable(googletest)
    add_executable(croptop_native_tests
        native_tests.cpp
        native_tests_codec_format.cpp
        native_tests_resample.cpp
        native_tests_layer_pixel_ops.cpp
        native_tests_format_fixtures.cpp
        native_tests_format_robustness.cpp
        native_tests_selection.cpp
        native_tests_selection_color.cpp
        native_tests_selection_modify.cpp
        native_tests_selection_allocator.cpp
        native_tests_object_select_allocator.cpp
        native_tests_selection_quick.cpp
        native_tests_selection_quick_safety.cpp
        native_tests_content_aware_fill.cpp
        native_tests_gradient_fill.cpp
        native_tests_brush_engine.cpp
        native_tests_adjustment_lut_bridge.cpp
        native_tests_adjustment_preview_scheduler.cpp
        native_tests_color_management.cpp
        native_tests_stroke_compositor.cpp
        native_tests_develop_tone.cpp
        native_tests_develop_color.cpp
        native_tests_develop_detail.cpp
        native_tests_develop_pipeline.cpp
        native_tests_develop_settings_codec.cpp
        native_tests_filter_dispatch.cpp
        native_tests_selection_golden.cpp
        native_tests_layer_styles_codec.cpp
        native_tests_develop_mask_blend.cpp
        native_tests_develop_batch.cpp
        native_tests_develop_masks.cpp
        native_tests_develop_mask_ops.cpp
        native_tests_develop_upright_bridge.cpp
        native_tests_develop_xmp.cpp
        native_tests_raw_demosaic.cpp
        native_tests_raw_xtrans.cpp
        native_tests_raw_xtrans_dispatch.cpp
        native_tests_shape_layer.cpp
        native_tests_jni_shape_layer.cpp
        native_tests_shape_render_adapter.cpp
        native_tests_codec_extra.cpp
        native_tests_transform_session.cpp
        native_tests_defringe.cpp
        native_tests_lens_profile.cpp
        native_tests_lens_blur.cpp
        native_tests_selection_magnetic.cpp
        native_tests_smart_object.cpp
        native_tests_stack_merge.cpp
        native_tests_puppet_warp.cpp
        native_tests_panorama.cpp
        native_tests_stroke_symmetry.cpp
        native_tests_text_bidi.cpp
        native_tests_text_effects.cpp
        native_tests_text_layout.cpp
        native_tests_truetype_font.cpp
        native_tests_font_variations.cpp
        native_tests_abr.cpp
        native_tests_adjustment_params.cpp
        native_tests_adjustments.cpp
        native_tests_crop.cpp
        native_tests_fill.cpp
        native_tests_filters_blur.cpp
        native_tests_filters_distort.cpp
        native_tests_filters_noise.cpp
        native_tests_heal.cpp
        native_tests_histogram.cpp
        native_tests_image_metadata.cpp
        native_tests_layer_mask.cpp
        native_tests_layer_styles.cpp
        native_tests_lens_correction.cpp
        native_tests_liquify.cpp
        native_tests_mesh_warp.cpp
        native_tests_lut.cpp
        native_tests_psd_compose.cpp
        native_tests_psd.cpp
        native_tests_psd_import.cpp
        native_tests_psd_layer_data.cpp
        native_tests_psd_writer.cpp
        native_tests_refine_edge.cpp
        native_tests_mask_upsample.cpp
        native_tests_gpu_quirks.cpp
        native_tests_avif.cpp
        native_tests_ultra_hdr.cpp
        native_tests_retouch.cpp
        native_tests_stroke_stabilizer.cpp
        native_tests_tiff.cpp
        native_tests_upright.cpp
        native_tests_vector_path.cpp
        native_tests_vector_boolean.cpp
        native_tests_curve_fit.cpp
        native_tests_stress.cpp
        native_tests_croptop_embedded.cpp
        native_tests_raw_decode.cpp
        native_tests_raw_open.cpp
        native_tests_raw_lens.cpp
        native_tests_raw_smart_object.cpp
        native_tests_layer_mask_ops.cpp
        native_tests_render_graph_masks.cpp
        native_tests_blend_if.cpp
        native_tests_content_aware_sampling.cpp
        layer_style_attachment.cpp
    )
    if(CROPTOP_GPU_DEVELOP)
        target_sources(croptop_native_tests PRIVATE native_tests_develop_color_gpu.cpp native_tests_develop_tone_gpu.cpp native_tests_develop_gpu.cpp native_tests_histogram_gpu.cpp native_tests_vulkan_blend.cpp native_tests_gpu_service.cpp)
    endif()
    target_compile_options(croptop_native_tests PRIVATE -Wall -Wextra -Wpedantic -Werror)
    target_link_libraries(croptop_native_tests PRIVATE croptop_image_metrics croptop_engine_core GTest::gtest_main)
    add_test(NAME croptop_native_tests COMMAND croptop_native_tests)
    add_executable(croptop_raw_check tools/raw_check.cpp)
    target_compile_options(croptop_raw_check PRIVATE -Wall -Wextra -Wpedantic -Werror)
    target_link_libraries(croptop_raw_check PRIVATE croptop_engine_core)
endif()

if(CROPTOP_BUILD_FUZZERS)
    if(NOT CROPTOP_FETCH_NATIVE_DEPS)
        message(FATAL_ERROR "CROPTOP_BUILD_FUZZERS requires CROPTOP_FETCH_NATIVE_DEPS")
    endif()
    if(NOT CMAKE_CXX_COMPILER_ID MATCHES "Clang")
        message(FATAL_ERROR "CROPTOP_BUILD_FUZZERS requires Clang with libFuzzer support")
    endif()
    target_compile_options(croptop_engine_core PRIVATE -fsanitize=fuzzer-no-link,address)
    add_executable(croptop_decoder_fuzzer decoder_fuzzer.cpp)
    target_compile_options(croptop_decoder_fuzzer PRIVATE
        -Wall -Wextra -Wpedantic -Werror -fsanitize=fuzzer-no-link,address)
    target_link_options(croptop_decoder_fuzzer PRIVATE -fsanitize=fuzzer,address)
    target_link_libraries(croptop_decoder_fuzzer PRIVATE croptop_engine_core)
    add_executable(croptop_format_fuzzer croptop_format_fuzzer.cpp)
    target_compile_options(croptop_format_fuzzer PRIVATE
        -Wall -Wextra -Wpedantic -Werror -fsanitize=fuzzer-no-link,address -fno-omit-frame-pointer)
    target_link_options(croptop_format_fuzzer PRIVATE -fsanitize=fuzzer,address)
    target_link_libraries(croptop_format_fuzzer PRIVATE croptop_engine_core)
    add_subdirectory(fuzz)
endif()

if(CROPTOP_BUILD_BENCHMARKS)
    if(NOT CROPTOP_FETCH_NATIVE_DEPS)
        message(FATAL_ERROR "CROPTOP_BUILD_BENCHMARKS requires CROPTOP_FETCH_NATIVE_DEPS")
    endif()
    set(BENCHMARK_ENABLE_TESTING OFF CACHE BOOL "" FORCE)
    set(BENCHMARK_ENABLE_GTEST_TESTS OFF CACHE BOOL "" FORCE)
    FetchContent_Declare(googlebenchmark
        URL https://github.com/google/benchmark/archive/refs/tags/v1.9.5.tar.gz
        URL_HASH SHA256=9631341c82bac4a288bef951f8b26b41f69021794184ece969f8473977eaa340
    )
    FetchContent_MakeAvailable(googlebenchmark)
    add_executable(croptop_native_benchmarks native_benchmarks.cpp)
    target_compile_options(croptop_native_benchmarks PRIVATE -Wall -Wextra -Wpedantic -Werror)
    target_link_libraries(croptop_native_benchmarks PRIVATE croptop_image_metrics croptop_engine_core benchmark::benchmark_main)
endif()
