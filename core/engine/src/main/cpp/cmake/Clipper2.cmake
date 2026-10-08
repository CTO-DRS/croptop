# Clipper2 (Boost Software License 1.0) for geometric path booleans and offsetting: the pinned release
# archive built as croptop_clipper2. Only the polygon-clipping and offsetting sources are compiled (no Minkowski/DLL export).
include(FetchContent)

FetchContent_Declare(clipper2
    URL https://github.com/AngusJohnson/Clipper2/archive/refs/tags/Clipper2_1.5.4.tar.gz
    URL_HASH SHA256=9d8a35a29d04cd1b7b45f542c0ba48015feece1210036ea9e4efaad3140af4b0
)
# The archive has a CMakeLists in CPP/, but it builds tests and examples; we only need the library sources.
FetchContent_GetProperties(clipper2)
if(NOT clipper2_POPULATED)
    FetchContent_Populate(clipper2)
endif()

set(CROPTOP_CLIPPER2_DIR "${clipper2_SOURCE_DIR}/CPP/Clipper2Lib")
add_library(croptop_clipper2 STATIC
    "${CROPTOP_CLIPPER2_DIR}/src/clipper.engine.cpp"
    "${CROPTOP_CLIPPER2_DIR}/src/clipper.offset.cpp"
    "${CROPTOP_CLIPPER2_DIR}/src/clipper.rectclip.cpp")
target_include_directories(croptop_clipper2 PUBLIC "${CROPTOP_CLIPPER2_DIR}/include")
target_compile_options(croptop_clipper2 PRIVATE -w)
