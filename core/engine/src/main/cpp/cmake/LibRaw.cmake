# LibRaw for camera-raw decode: the pinned release archive, built as croptop_libraw.
# LibRaw is dual-licensed LGPL-2.1 / CDDL-1.0; CropTop takes the CDDL-1.0 option so it can link statically
# (see the LibRaw row of the third-party table). Optional demosaic packs (GPL), RawSpeed and the Adobe DNG SDK are not used: the DNG SDK and
# RawSpeed glue files compile to nothing without their macros.
include(FetchContent)

FetchContent_Declare(libraw
    URL https://github.com/LibRaw/LibRaw/archive/refs/tags/0.22.2.tar.gz
    URL_HASH SHA256=627928088300ecde6ca91ffd202e189203f04ad61ad12f0fe9dc57b9a7a0fb3c
)
# LibRaw ships no CMakeLists, so this only downloads and unpacks it.
FetchContent_MakeAvailable(libraw)

file(GLOB_RECURSE CROPTOP_LIBRAW_SOURCES CONFIGURE_DEPENDS "${libraw_SOURCE_DIR}/src/*.cpp")
# The *_ph.cpp files are placeholder stubs for the modular build and clash with the real implementations.
list(FILTER CROPTOP_LIBRAW_SOURCES EXCLUDE REGEX "_ph\\.cpp$")
add_library(croptop_libraw STATIC ${CROPTOP_LIBRAW_SOURCES})
target_include_directories(croptop_libraw PUBLIC "${libraw_SOURCE_DIR}")
# USE_JPEG: lossy-JPEG DNG and Kodak DC120; USE_ZLIB: Deflate DNG. No lcms: colour is ours (color_management).
target_compile_definitions(croptop_libraw PUBLIC LIBRAW_NODLL PRIVATE USE_ZLIB USE_JPEG NO_LCMS)
# Third-party code: its own warnings are not ours to fix.
target_compile_options(croptop_libraw PRIVATE -w -fvisibility=hidden)
target_link_libraries(croptop_libraw PUBLIC jpeg-static)
if(ANDROID)
    target_link_libraries(croptop_libraw PUBLIC z)
else()
    find_package(ZLIB REQUIRED)
    target_link_libraries(croptop_libraw PUBLIC ZLIB::ZLIB)
endif()
