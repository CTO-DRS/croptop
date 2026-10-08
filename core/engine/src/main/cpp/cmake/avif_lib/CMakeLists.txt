# Libavif 1.3.0 library sources (+ its bundled libyuv subset) compiled against libaom; see ../Avif.cmake.
cmake_minimum_required(VERSION 3.22.1)
project(croptop_avif_lib C)

set(_avif "${CROPTOP_AVIF_SOURCE_DIR}")
add_library(croptop_avif_lib STATIC
    "${_avif}/src/alpha.c" "${_avif}/src/avif.c" "${_avif}/src/colr.c" "${_avif}/src/colrconvert.c" "${_avif}/src/diag.c"
    "${_avif}/src/exif.c" "${_avif}/src/gainmap.c" "${_avif}/src/io.c" "${_avif}/src/mem.c" "${_avif}/src/obu.c"
    "${_avif}/src/properties.c" "${_avif}/src/rawdata.c" "${_avif}/src/read.c" "${_avif}/src/reformat.c"
    "${_avif}/src/reformat_libsharpyuv.c" "${_avif}/src/reformat_libyuv.c" "${_avif}/src/scale.c" "${_avif}/src/stream.c"
    "${_avif}/src/utils.c" "${_avif}/src/write.c" "${_avif}/src/codec_aom.c"
    # libavif's bundled subset of libyuv (BSD-3-Clause): scaling and plane copies used by the reformat code.
    "${_avif}/third_party/libyuv/source/scale.c" "${_avif}/third_party/libyuv/source/scale_common.c"
    "${_avif}/third_party/libyuv/source/scale_any.c" "${_avif}/third_party/libyuv/source/row_common.c"
    "${_avif}/third_party/libyuv/source/planar_functions.c")
target_include_directories(croptop_avif_lib PUBLIC "${_avif}/include")
target_include_directories(croptop_avif_lib PRIVATE "${CROPTOP_AOM_SOURCE_DIR}" "${_avif}/third_party/libyuv/include")
target_compile_definitions(croptop_avif_lib PRIVATE AVIF_CODEC_AOM=1 AVIF_CODEC_AOM_ENCODE=1 AVIF_CODEC_AOM_DECODE=1)
target_compile_options(croptop_avif_lib PRIVATE -w -Os -ffunction-sections -fdata-sections)
target_link_libraries(croptop_avif_lib PUBLIC aom)
