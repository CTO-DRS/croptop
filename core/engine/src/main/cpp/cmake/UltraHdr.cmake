# Ultra HDR JPEG export: libultrahdr 1.4.0 (Apache-2.0; its image_io helper is Apache-2.0 and modp_b64 is
# BSD-2-Clause). The library sources are compiled directly against croptop's libjpeg-turbo (jpeg-static), no third-party build system.
# Gain-map metadata is written as ISO 21496-1 and XMP so both Android and Google Photos read it. NEON paths on arm64 only.
include(FetchContent)

FetchContent_Declare(libultrahdr
    URL https://github.com/google/libultrahdr/archive/refs/tags/v1.4.0.tar.gz
    URL_HASH SHA256=e7e1252e2c44d8ed6b99ee0f67a3caf2d8a61c43834b13b1c3cd485574c03ab9
)
FetchContent_GetProperties(libultrahdr)
if(NOT libultrahdr_POPULATED)
    FetchContent_Populate(libultrahdr)
endif()
set(_uhdr "${libultrahdr_SOURCE_DIR}")
file(GLOB _uhdr_core "${_uhdr}/lib/src/*.cpp")
file(GLOB_RECURSE _uhdr_imageio "${_uhdr}/third_party/image_io/src/*.cc")
set(_uhdr_sources ${_uhdr_core} ${_uhdr_imageio})
add_library(croptop_uhdr STATIC ${_uhdr_sources})
target_include_directories(croptop_uhdr
    PUBLIC "${_uhdr}"                       # ultrahdr_api.h
    PRIVATE "${_uhdr}/lib/include" "${_uhdr}/lib" "${_uhdr}/third_party/image_io/includes"
            "${_uhdr}/third_party/image_io/src/modp_b64" "${_uhdr}/third_party/image_io/src/modp_b64/modp_b64")
target_compile_definitions(croptop_uhdr PRIVATE UHDR_WRITE_XMP UHDR_WRITE_ISO)
if(ANDROID AND ANDROID_ABI STREQUAL "arm64-v8a")
    file(GLOB _uhdr_neon "${_uhdr}/lib/src/dsp/arm/*.cpp")
    target_sources(croptop_uhdr PRIVATE ${_uhdr_neon})
    target_compile_definitions(croptop_uhdr PRIVATE UHDR_ENABLE_INTRINSICS)
endif()
target_compile_options(croptop_uhdr PRIVATE -w -include cstdint -Os -ffunction-sections -fdata-sections)
target_link_libraries(croptop_uhdr PUBLIC jpeg-static)
if(NOT ANDROID)
    find_package(Threads REQUIRED)
    target_link_libraries(croptop_uhdr PUBLIC Threads::Threads)
endif()
