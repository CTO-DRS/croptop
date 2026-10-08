# All dependencies are fetched as immutable, checksum-verified release archives.
# Prefer system Android zlib; NDK ships it in the sysroot.
include(FetchContent)
include(ExternalProject)

set(FETCHCONTENT_QUIET OFF)
set(ZSTD_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(ZSTD_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(ZSTD_BUILD_SHARED OFF CACHE BOOL "" FORCE)
set(ZSTD_BUILD_STATIC ON CACHE BOOL "" FORCE)
FetchContent_Declare(zstd
    URL https://github.com/facebook/zstd/releases/download/v1.5.7/zstd-1.5.7.tar.gz
    URL_HASH SHA256=eb33e51f49a15e023950cd7825ca74a4a2b43db8354825ac24fc1b7ee09e6fa3
    SOURCE_SUBDIR build/cmake
)
FetchContent_MakeAvailable(zstd)

set(LCMS2_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LCMS2_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LCMS2_BUILD_SHARED OFF CACHE BOOL "" FORCE)
set(LCMS2_BUILD_STATIC ON CACHE BOOL "" FORCE)
FetchContent_Declare(lcms2
    URL https://downloads.sourceforge.net/project/lcms/lcms/2.19/lcms2-2.19.tar.gz
    URL_HASH SHA256=49e7e134e4299733dd0eda434fa468997a28ab3d33fa397c642b03644f552216
)
FetchContent_MakeAvailable(lcms2)

# libjpeg-turbo deliberately rejects add_subdirectory integration; build it as
# an isolated project and expose only its static archive to the engine.
set(CROPTOP_JPEG_TURBO_SOURCE_DIR "${CMAKE_BINARY_DIR}/_deps/libjpeg-turbo-src")
set(CROPTOP_JPEG_TURBO_INSTALL_DIR "${CMAKE_BINARY_DIR}/_deps/libjpeg-turbo-install")
file(MAKE_DIRECTORY "${CROPTOP_JPEG_TURBO_INSTALL_DIR}/include")
ExternalProject_Add(croptop_libjpeg_turbo
    URL https://github.com/libjpeg-turbo/libjpeg-turbo/releases/download/3.2.0/libjpeg-turbo-3.2.0.tar.gz
    URL_HASH SHA256=6f30092cef9fb839779646608f4ee14ae3cbac989c47fa05e841b0841f09878e
    SOURCE_DIR "${CROPTOP_JPEG_TURBO_SOURCE_DIR}"
    INSTALL_DIR "${CROPTOP_JPEG_TURBO_INSTALL_DIR}"
    CMAKE_ARGS
        -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
        -DANDROID_ABI=${ANDROID_ABI}
        -DANDROID_PLATFORM=${ANDROID_PLATFORM}
        -DCMAKE_BUILD_TYPE=${CMAKE_BUILD_TYPE}
        -DCMAKE_INSTALL_PREFIX=<INSTALL_DIR>
        -DENABLE_SHARED=OFF
        -DENABLE_STATIC=ON
        -DWITH_TURBOJPEG=OFF
        -DWITH_TOOLS=OFF
        -DWITH_TESTS=OFF
    BUILD_BYPRODUCTS "${CROPTOP_JPEG_TURBO_INSTALL_DIR}/lib/libjpeg.a"
)
add_library(jpeg-static STATIC IMPORTED GLOBAL)
set_target_properties(jpeg-static PROPERTIES
    IMPORTED_LOCATION "${CROPTOP_JPEG_TURBO_INSTALL_DIR}/lib/libjpeg.a"
    INTERFACE_INCLUDE_DIRECTORIES "${CROPTOP_JPEG_TURBO_INSTALL_DIR}/include"
)
add_dependencies(jpeg-static croptop_libjpeg_turbo)

set(PNG_TESTS OFF CACHE BOOL "" FORCE)
set(PNG_TOOLS OFF CACHE BOOL "" FORCE)
set(PNG_SHARED OFF CACHE BOOL "" FORCE)
set(PNG_STATIC ON CACHE BOOL "" FORCE)
FetchContent_Declare(libpng
    URL https://downloads.sourceforge.net/project/libpng/libpng16/1.6.58/libpng-1.6.58.tar.xz
    URL_HASH SHA256=28eb403f51f0f7405249132cecfe82ea5c0ef97f1b32c5a65828814ae0d34775
)
FetchContent_MakeAvailable(libpng)

set(WEBP_BUILD_ANIM_UTILS OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_CWEBP OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_DWEBP OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_GIF2WEBP OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_IMG2WEBP OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_VWEBP OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_WEBPINFO OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_LIBWEBPMUX ON CACHE BOOL "" FORCE)
set(WEBP_BUILD_EXTRAS OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WEBP_ENABLE_SIMD ON CACHE BOOL "" FORCE)
FetchContent_Declare(libwebp
    URL https://storage.googleapis.com/downloads.webmproject.org/releases/webp/libwebp-1.6.0.tar.gz
    URL_HASH SHA256=e4ab7009bf0629fd11982d4c2aa83964cf244cffba7347ecd39019a9e38c4564
)
FetchContent_MakeAvailable(libwebp)

set(tiff-tools OFF CACHE BOOL "" FORCE)
set(tiff-static ON CACHE BOOL "" FORCE)
set(tiff-tests OFF CACHE BOOL "" FORCE)
set(tiff-docs OFF CACHE BOOL "" FORCE)
set(tiff-contrib OFF CACHE BOOL "" FORCE)
set(tiff-tools-unix OFF CACHE BOOL "" FORCE)
set(tiff-tools-win OFF CACHE BOOL "" FORCE)
set(tiff-jbig OFF CACHE BOOL "" FORCE)
set(tiff-jpeg OFF CACHE BOOL "" FORCE)
set(tiff-jpeg12 OFF CACHE BOOL "" FORCE)
set(tiff-lzma OFF CACHE BOOL "" FORCE)
set(tiff-webp OFF CACHE BOOL "" FORCE)
set(tiff-zstd OFF CACHE BOOL "" FORCE)
set(tiff-zlib ON CACHE BOOL "" FORCE)
FetchContent_Declare(libtiff
    URL https://download.osgeo.org/libtiff/tiff-4.7.2.tar.xz
    URL_HASH SHA256=4996f0c4f93094719b1ca5c6279b20e588773ba8a247533e486416fb662ddb88
)
FetchContent_MakeAvailable(libtiff)
