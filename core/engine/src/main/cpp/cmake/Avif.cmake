# AVIF: libavif 1.3.0 (BSD-2-Clause) on libaom 3.13.1 (BSD-2-Clause + AOM patent license) for both encode and
# decode. libaom is built through its own CMake without assembly (portable, no nasm) and without tools/examples/tests; libavif's
# library sources are compiled here directly so no git checkout of codecs is needed. Produces croptop_avif.
include(FetchContent)

FetchContent_Declare(libaom
    URL https://storage.googleapis.com/aom-releases/libaom-3.13.1.tar.gz
    URL_HASH SHA256=19e45a5a7192d690565229983dad900e76b513a02306c12053fb9a262cbeca7d
)
FetchContent_Declare(libavif
    URL https://github.com/AOMediaCodec/libavif/archive/refs/tags/v1.3.0.tar.gz
    URL_HASH SHA256=0a545e953cc049bf5bcf4ee467306a2f113a75110edf59e61248873101cd26c1
)

set(ENABLE_TESTS OFF CACHE BOOL "" FORCE)
set(ENABLE_EXAMPLES OFF CACHE BOOL "" FORCE)
set(ENABLE_DOCS OFF CACHE BOOL "" FORCE)
set(ENABLE_TOOLS OFF CACHE BOOL "" FORCE)
set(ENABLE_NASM OFF CACHE BOOL "" FORCE)
set(AOM_TARGET_CPU generic CACHE STRING "" FORCE)
set(CONFIG_RUNTIME_CPU_DETECT 0 CACHE STRING "" FORCE)
set(CONFIG_MULTITHREAD 1 CACHE STRING "" FORCE)
FetchContent_GetProperties(libaom)
if(NOT libaom_POPULATED)
    FetchContent_Populate(libaom)
    # libaom's configure step appends its own warning and `-std=c99` / `-std=c++17` flags to the *global* CMake flag caches, which
    # would then apply to every other target (lcms2 and libpng would compile as strict c99 and fail on gmtime_r). Snapshot the
    # caches and restore them once libaom has been added.
    set(_flag_vars CMAKE_C_FLAGS CMAKE_CXX_FLAGS)
    foreach(_cfg DEBUG RELEASE RELWITHDEBINFO MINSIZEREL)
        list(APPEND _flag_vars CMAKE_C_FLAGS_${_cfg} CMAKE_CXX_FLAGS_${_cfg})
    endforeach()
    foreach(_v IN LISTS _flag_vars)
        set(_saved_${_v} "${${_v}}")
    endforeach()
    add_subdirectory("${libaom_SOURCE_DIR}" "${libaom_BINARY_DIR}" EXCLUDE_FROM_ALL)
    foreach(_v IN LISTS _flag_vars)
        set(${_v} "${_saved_${_v}}" CACHE STRING "" FORCE)
        set(${_v} "${_saved_${_v}}")
    endforeach()
    # Size over speed for the codec: -Os shrinks the linked encoder+decoder from about 4.5 MB to 2.7 MB (arm64, measured).
    get_directory_property(_aom_targets DIRECTORY "${libaom_SOURCE_DIR}" BUILDSYSTEM_TARGETS)
    foreach(_t IN LISTS _aom_targets)
        get_target_property(_type ${_t} TYPE)
        if(_type STREQUAL "STATIC_LIBRARY" OR _type STREQUAL "OBJECT_LIBRARY")
            target_compile_options(${_t} PRIVATE -Os -ffunction-sections -fdata-sections)
        endif()
    endforeach()
endif()

FetchContent_GetProperties(libavif)
if(NOT libavif_POPULATED)
    FetchContent_Populate(libavif)
endif()
# The C sources live in a subdirectory project so C is enabled in that scope only: enabling C at the CXX-only top level changes the
# default C standard flags of the other dependencies (lcms2 would start compiling as strict -std=c99 and fail).
set(CROPTOP_AVIF_SOURCE_DIR "${libavif_SOURCE_DIR}")
set(CROPTOP_AOM_SOURCE_DIR "${libaom_SOURCE_DIR}")
add_subdirectory("${CMAKE_CURRENT_LIST_DIR}/avif_lib" "${CMAKE_BINARY_DIR}/croptop_avif_lib")
